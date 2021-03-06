package com.coinblesk.server.service;

import static com.coinblesk.server.enumerator.EventType.MICRO_PAYMENT_POT_EXHAUSTED;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.coinblesk.server.config.AppConfig;
import com.coinblesk.server.dao.AccountRepository;
import com.coinblesk.server.dao.TimeLockedAddressRepository;
import com.coinblesk.server.entity.Account;
import com.coinblesk.server.entity.TimeLockedAddressEntity;
import com.coinblesk.server.enumerator.EventType;
import com.coinblesk.server.exceptions.BusinessException;
import com.coinblesk.server.exceptions.InvalidAmountException;
import com.coinblesk.server.exceptions.InvalidNonceException;
import com.coinblesk.server.exceptions.InvalidRequestException;
import com.coinblesk.server.exceptions.UserNotFoundException;
import com.coinblesk.server.utils.CoinUtils;
import com.coinblesk.util.DTOUtils;
import com.coinblesk.util.InsufficientFunds;
import com.google.common.annotations.VisibleForTesting;

import lombok.Data;
import lombok.NonNull;

@Service
public class MicropaymentService {
	private final static Logger LOG = LoggerFactory.getLogger(MicropaymentService.class);
	private final AccountRepository accountRepository;
	private final TimeLockedAddressRepository timeLockedAddressRepository;
	private final AppConfig appConfig;
	private final EventService eventService;
	private final WalletService walletService;
	private final AccountService accountService;
	private final FeeService feeService;
	private final ForexBitcoinService forexService;

	@Autowired
	public MicropaymentService(AccountRepository accountRepository, TimeLockedAddressRepository
		timeLockedAddressRepository, AppConfig appConfig, EventService eventService, WalletService walletService,
							   AccountService accountService, FeeService feeService, ForexBitcoinService forexService) {
		this.accountRepository = accountRepository;
		this.timeLockedAddressRepository = timeLockedAddressRepository;
		this.appConfig = appConfig;
		this.eventService = eventService;
		this.walletService = walletService;
		this.accountService = accountService;
		this.feeService = feeService;
		this.forexService = forexService;

		unlockAccountsOnMinedChannelTransactions();
	}

	/**
	 * Result of a successful virtual payment.
	 * Contains the new balances and both private keys of the server.
	 * These can be used to sign the resulting information.
	 */
	@Data
	public class VirtualPaymentResult {
		private final long newBalanceSender;
		private final byte[] serverPrivateKeyForSender;
		private final long newBalanceReceiver;
		private final byte[] serverPrivateKeyForReceiver;
	}

	@Transactional(isolation = Isolation.SERIALIZABLE)
	@Retryable(value = TransientDataAccessException.class, maxAttempts = 5)
	public VirtualPaymentResult virtualPayment(@NonNull ECKey keySender, @NonNull ECKey keyReceiver, long amount, long
		requestNonce) throws InvalidNonceException, UserNotFoundException, InvalidAmountException, InsufficientFunds,
		InvalidRequestException {

		// Sender and receiver must be different entities
		if (keySender.getPublicKeyAsHex().equals(keyReceiver.getPublicKeyAsHex()))
			throw new InvalidRequestException("The sender and receiver cannot be the same entities");

		final Account sender = accountRepository.findByClientPublicKey(keySender.getPubKey());
		if (sender == null)
			throw new UserNotFoundException(keySender.getPublicKeyAsHex());

		// Abort if the nonce is not fresh, we allow only higher nonces than in the database
		// The nonce is generated by the client as unix epoch time in milliseconds.
		// This prevents possible replay attacks:
		// If we receive the same nonce as in the database, the request was probably sent two times
		// If the nonce in the database is larger than the request, we probably got an old request sent again.
		if (requestNonce <= sender.nonce())
			throw new InvalidNonceException("Invalid nonce. Request already processed?");

		// Fail if amount is invalid
		if (amount < 1)
			throw new InvalidAmountException("Invalid amount. Must be 1 or larger.");

		// Check for sufficient funds
		if (amount > sender.virtualBalance())
			throw new InsufficientFunds("Insufficient funds, only " + sender.virtualBalance() + " satoshis available");

		// Get receiver from database
		final Account receiver = accountRepository.findByClientPublicKey(keyReceiver.getPubKey());
		if (receiver == null)
			throw new UserNotFoundException(keyReceiver.getPublicKeyAsHex());

		// Do the transfer
		final long senderOldBalance = sender.virtualBalance();
		final long receiverOldBalance = receiver.virtualBalance();
		sender.virtualBalance(senderOldBalance - amount);
		receiver.virtualBalance(receiverOldBalance + amount);

		// Guarantee that this request is only processed once
		sender.nonce(requestNonce);

		accountRepository.save(sender);
		accountRepository.save(receiver);

		// Return the new balances and the keys for sender and receiver that can be used for signing
		return new VirtualPaymentResult(sender.virtualBalance(), sender.serverPrivateKey(), receiver.virtualBalance(),
			receiver.serverPrivateKey());
	}

	// Represents a successful micro payment
	public static class MicroPaymentResult {
		public ECKey privateKeyServer;
		public long newBalanceReceiver;
		public Transaction broadcastedTx; // Non-null if the channel was closed
	}

	@Transactional(isolation = Isolation.SERIALIZABLE)
	public MicroPaymentResult microPayment(ECKey senderPublicKey, String receiverPublicKey, String txInHex, Long
		amount, Long nonce) throws Exception {

		// Parse the transaction
		byte[] txInBytes = DTOUtils.fromHex(txInHex);
		Transaction tx = new Transaction(appConfig.getNetworkParameters(), txInBytes);
		tx.verify(); // Checks for no input or outputs and no negative values.

		// Check account for locked and nonce
		Account accountSender = accountService.getByClientPublicKey(senderPublicKey.getPubKey());
		if (accountSender == null) {
			throw new RuntimeException("Unknown sender");
		}
		if (accountSender.isLocked()) {
			throw new RuntimeException("Channel is locked");
		}
		if (nonce <= accountSender.nonce()) {
			throw new RuntimeException("Invalid nonce");
		}

		// Check inputs and retrieve lastest time this transaction must be broadcast
		final long broadcastBefore = checkInputs(tx, accountSender, feeService.fee());

		// Amount given to server must be equal to last channel or higher
		final ECKey serverPubKey = appConfig.getMicroPaymentPotPrivKey();
		final Coin amountToServer = getOutputForP2PK(txInBytes, serverPubKey);
		final Coin actualAmountSent = amountToServer.minus(
			getOutputForP2PK(accountSender.getChannelTransaction(), serverPubKey));
		if (actualAmountSent.isNegative())
			throw new RuntimeException("Amount to server must more than in open channel.");

		if (amount.equals(0L) && receiverPublicKey.equals("")) { // External payment
			accountSender
				.nonce(nonce)
				.channelTransaction(tx.bitcoinSerialize())
				.broadcastBefore(Instant.now().getEpochSecond());
			accountRepository.save(accountSender);

			closeMicroPaymentChannel(senderPublicKey);

			MicroPaymentResult res = new MicroPaymentResult();
			res.broadcastedTx = tx;
			return res;

		} else { // Payment to other coinblesk user.

			// Check for valid amount
			final Coin amountFromRequest = Coin.valueOf(amount);
			if (amountFromRequest.isNegative() || amountFromRequest.isZero()) {
				throw new RuntimeException("Can't send zero or negative amont");
			}
			if (!actualAmountSent.equals(amountFromRequest)) {
				throw new RuntimeException("Invalid amount. " + amountFromRequest + " requested but " + actualAmountSent +
					" given");
			}

			final BigDecimal btc_usd = forexService.getBitstampCurrentRateBTCUSD().getRate();
			final long channelAmountInUSD = btc_usd.divide(new BigDecimal(100000000))
				.multiply(new BigDecimal(amountToServer.getValue())).longValue();
			if (channelAmountInUSD > appConfig.getMaximumChannelAmountUSD()) {
				throw new RuntimeException("Maximum channel value reached");
			}

			// Make sure the receiving public key is known to the server
			ECKey receiverKey = DTOUtils.getECKeyFromHexPublicKey(receiverPublicKey);
			final Account accountReceiver = accountService.getByClientPublicKey(receiverKey.getPubKey());
			if (accountReceiver == null) {
				throw new RuntimeException("Receiver is unknown to server");
			}
			// Sending to oneself is not allowed
			if (accountReceiver.equals(accountSender)) {
				throw new RuntimeException("Sender and receiver must be different");
			}

			// Execute micro payment
			accountSender
				.nonce(nonce)
				.channelTransaction(tx.bitcoinSerialize())
				.broadcastBefore(broadcastBefore);
			final long newAmountReceiver = accountReceiver.virtualBalance() + actualAmountSent.getValue();
			accountReceiver
				.virtualBalance(newAmountReceiver);

			MicroPaymentResult res = new MicroPaymentResult();
			res.newBalanceReceiver = newAmountReceiver;
			res.privateKeyServer = ECKey.fromPrivate(accountReceiver.serverPrivateKey());
			return res;
		}
	}

	/***
	 * Checks all inputs for validity.
	 *
	 * @param tx The transaction to check
	 * @param accountSender Every input must be from an address from this account
	 * @param neededFeeInSatoshi Fee in Satoshi/Byte that the given transaction should have
	 * @return long (UNIX timestamp) that indicates then when the first address used is unlocked
	 */
	private long checkInputs(Transaction tx, Account accountSender, int neededFeeInSatoshi) {
		Coin valueOfInputs = Coin.ZERO;
		long earliestLockTime = Long.MAX_VALUE;

		for (int i=0; i<tx.getInputs().size(); i++) {
			TransactionInput input = tx.getInput(i);

			TransactionOutput out = walletService.findOutputFor(input);
			if (out == null) {
				throw new RuntimeException("Transaction spends unknown UTXOs");
			}
			valueOfInputs = valueOfInputs.add(out.getValue());
			if (!out.isAvailableForSpending()) {
				throw new RuntimeException("Input is already spent");
			}
			if (out.getParentTransactionDepthInBlocks() < 1) {
				throw new RuntimeException("UTXO must be mined");
			}

			Address fromAddress = out.getAddressFromP2SH(appConfig.getNetworkParameters());
			if (fromAddress == null) {
				throw new RuntimeException("Transaction must spent P2SH addresses");
			}

			TimeLockedAddressEntity tlaEntity = timeLockedAddressRepository.findByAddressHash(fromAddress.getHash160());
			if (tlaEntity == null) {
				throw new RuntimeException("Used TLA inputs are not known to server");
			}
			if (!tlaEntity.getAccount().equals(accountSender)) {
				throw new RuntimeException("Inputs must be from sender account");
			}

			final Instant minimumLockedUntil = Instant.now()
				.plus(Duration.ofSeconds(appConfig.getMinimumLockTimeSeconds()));
			if (Instant.ofEpochSecond(tlaEntity.getLockTime()).isBefore(minimumLockedUntil)) {
				throw new RuntimeException("Inputs must be locked at least until " + minimumLockedUntil);
			}
			earliestLockTime = Math.min(earliestLockTime, tlaEntity.getLockTime());

			Script spendingScript = input.getScriptSig();
			if (spendingScript.getChunks().size() == 0) {
				throw new RuntimeException("Input was not signed");
			}
			if (spendingScript.getChunks().size() != 1 || !spendingScript.getChunks().get(0).isPushData()) {
				throw new RuntimeException("Signature for input had wrong format");
			}
			byte[] redeemScript = tlaEntity.getRedeemScript();
			final ECKey serverPrivateKey = ECKey.fromPrivateAndPrecalculatedPublic(accountSender.serverPrivateKey(),
				accountSender.serverPublicKey());
			TransactionSignature serverSig = tx.calculateSignature(i, serverPrivateKey, redeemScript, SigHash.ALL,
				false);
			Script finalSig = new ScriptBuilder(spendingScript).data(serverSig.encodeToBitcoin()).smallNum(1).data
				(redeemScript).build();
			input.setScriptSig(finalSig);
			finalSig.correctlySpends(tx, i, out.getScriptPubKey(), Script.ALL_VERIFY_FLAGS);
		}

		// Check fee
		Coin givenFee = valueOfInputs.minus(tx.getOutputSum());
		Coin neededFee = Coin.valueOf(neededFeeInSatoshi * tx.bitcoinSerialize().length);
		if (givenFee.isLessThan(neededFee)) {
			throw new RuntimeException("Insufficient transaction fee. Given: " + givenFee.divide(tx.bitcoinSerialize()
				.length) + " satoshi per byte. Needed: " + neededFee.divide(tx.bitcoinSerialize().length));
		}

		return earliestLockTime;
	}

	public static class PayoutResponse {
		public long valuePaidOut;
		public String transaction;
	}
	@Transactional(isolation = Isolation.SERIALIZABLE)
	public PayoutResponse payOutVirtualBalance(ECKey accountOwner, String addressAsString) throws UserNotFoundException,
		InsufficientMoneyException, IOException, BusinessException, InsufficientFunds {
		final Address toAddress = Address.fromBase58(appConfig.getNetworkParameters(), addressAsString);

		final Account account = accountService.getByClientPublicKey(accountOwner.getPubKey());
		if (account == null)
			throw new UserNotFoundException(accountOwner.getPublicKeyAsHex());

		final Coin virtualBalance = Coin.valueOf(account.virtualBalance());
		if (!virtualBalance.isPositive())
			throw new InsufficientFunds();

		final Coin potValue = getMicroPaymentPotValue();
		LOG.info("Micropot value is {}", potValue);
		if (potValue.isLessThan(virtualBalance)) {
			eventService.warn(MICRO_PAYMENT_POT_EXHAUSTED, "Not enough coin in pot. " + virtualBalance + " needed " +
			"but only " + potValue + " available.");
			return new PayoutResponse();
		}

		Address changeAddress = appConfig.getMicroPaymentPotPrivKey().toAddress(appConfig.getNetworkParameters());

		// Estimate fee by creating a send request
		final Coin feePer1000Bytes = Coin.valueOf(feeService.fee() * 1000);
		SendRequest estimateRequest = SendRequest.to(toAddress, virtualBalance);
		estimateRequest.feePerKb = feePer1000Bytes;
		estimateRequest.changeAddress = changeAddress;
		walletService.getWallet().completeTx(estimateRequest);
		final Coin requiredFee = estimateRequest.tx.getFee();

		// Make actual request which subtracts fee from virtual balance
		final Coin coinSendToUser = virtualBalance.minus(requiredFee);
		SendRequest actualRequest = SendRequest.to(toAddress, coinSendToUser);
		actualRequest.feePerKb = feePer1000Bytes;
		actualRequest.changeAddress = changeAddress;
		Wallet.SendResult sendResult =  walletService.getWallet().sendCoins(actualRequest);

		// At this point we must consider the coins to be gone, even in case of failure as it has been transmitted
		// to the broadcaster.
		account.virtualBalance(0L);
		accountRepository.save(account);

		// Wait for actual broadcast to succeed
		Transaction broadcastedTx;
		try {
			broadcastedTx = sendResult.broadcastComplete.get();
		} catch (InterruptedException | ExecutionException e) {
			eventService.error(EventType.MICRO_PAYMENT_PAYOUT_ERROR, "Could not broadcast payout request "
				+ "for account " + accountOwner.getPublicKeyAsHex() + ". Transaction: "
				+ DTOUtils.toHex(sendResult.tx.bitcoinSerialize()) + " Reason:" + e.toString());
			e.printStackTrace();
			return new PayoutResponse();
		}

		PayoutResponse response = new PayoutResponse();
		response.transaction = DTOUtils.toHex(broadcastedTx.bitcoinSerialize());
		response.valuePaidOut = coinSendToUser.getValue();
		return response;
	}

	@VisibleForTesting
	@Transactional
	public void closeMicroPaymentChannel(ECKey forAccount) throws UserNotFoundException, InterruptedException,
		ExecutionException, TimeoutException {
		Account account = accountRepository.findByClientPublicKey(forAccount.getPubKey());
		if (account == null)
			throw new UserNotFoundException(forAccount.getPublicKeyAsHex());

		if (account.getChannelTransaction() == null) {
			final String logString = "Trying to close non-existing channel for user " + forAccount.getPublicKeyAsHex();
			eventService.error(EventType.MICRO_PAYMENT_CLOSING_OF_NONEXISTING_CHANNEL, logString);
			LOG.error(logString);
			throw new RuntimeException("No open channel");
		}

		account
		 	.locked(true)
			.broadcastBefore(Instant.now().getEpochSecond());
		accountRepository.save(account);

		Transaction tx = new Transaction(appConfig.getNetworkParameters(), account.getChannelTransaction());

		final String txAsString = DTOUtils.toHex(tx.bitcoinSerialize());
		final String logString = "Closing channel for "
			+ forAccount.getPublicKeyAsHex() + " with " + getPendingChannelValue(account)
			+ " pending BTC. Transaction: " + txAsString;
		eventService.info(EventType.MICRO_PAYMENT_CLOSING_CHANNEL, logString);
		appendToCloseLog(txAsString);
		try {
			walletService.broadCastAsync(tx).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			LOG.error("Error trying to close channel. Tx: {}", DTOUtils.toHex(tx.bitcoinSerialize()));
			eventService.fatal(EventType.MICRO_PAYMENT_COULD_NOT_BROADCAST_CHANNEL_TRANSACTION,
				"Error trying to close channel: " + logString);
			throw(e);
		}
	}

	private void appendToCloseLog(String txAsString) {
		try(BufferedWriter bw = Files.newBufferedWriter(Paths.get(appConfig.getConfigDir().getPath(),
				"broadcasted-transactions"), Charset.defaultCharset(), StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);) {
			bw.append(txAsString).append("\n");
		} catch (IOException e) {
			eventService.error(EventType.MICRO_PAYMENT_COULD_NOT_LOG_TO_FILE, "Tx:" + txAsString
				+ " Error:" + e.getMessage());
			LOG.error(e.toString());
		}
	}

	@Transactional()
	private void unlockAccount(ECKey accountPublicKey) {
		Account account = accountRepository.findByClientPublicKey(accountPublicKey.getPubKey())
			.channelTransaction(null)
			.locked(false);
		accountRepository.save(account);
	}

	@Scheduled(fixedDelayString = "${coinblesk.closeSchedulerInterval}000")
	public void checkForExpiringChannels() {
		final long threshold = Instant.now().plus(Duration.ofSeconds(appConfig.getMinimumLockTimeSeconds()))
			.getEpochSecond();
		accountRepository.findByBroadcastBeforeLessThanAndChannelTransactionNotNull(threshold).forEach(account -> {
			try {
				LOG.info("Closing channel " + DTOUtils.toHex(account.getChannelTransaction()));
				closeMicroPaymentChannel(ECKey.fromPublicOnly(account.clientPublicKey()));
			} catch (UserNotFoundException | TimeoutException | ExecutionException | InterruptedException e) {
				e.printStackTrace();
			}
		});
	}

	public Coin getPendingChannelValue() {
		return StreamSupport.stream(accountRepository.findAll().spliterator(), false)
			.filter(account -> account.getChannelTransaction() != null)
			.map(this::getPendingChannelValue)
			.reduce(Coin.ZERO, Coin::add);
	}

	public Coin getPendingChannelValue(Account account) {
		if (account.getChannelTransaction() == null)
			return Coin.ZERO;
		Transaction tx = new Transaction(appConfig.getNetworkParameters(), account.getChannelTransaction());
		Address serverAddress = appConfig.getMicroPaymentPotPrivKey().toAddress(appConfig.getNetworkParameters());
		return tx.getOutputs().stream()
			.filter(out -> Objects.equals(out.getAddressFromP2PKHScript(appConfig.getNetworkParameters()), serverAddress))
			.findFirst()
			.map(TransactionOutput::getValue)
			.orElse(Coin.ZERO);
	}

	public Coin getPendingChannelFees(Account account) {
		if (account.getChannelTransaction() == null) {
			return Coin.ZERO;
		}
		Transaction tx = new Transaction(appConfig.getNetworkParameters(), account.getChannelTransaction());

		long inputSum = 0L;
		long outputSum = 0L;

		for(TransactionInput in : tx.getInputs()) {
			inputSum += walletService.findOutputFor(in).getValue().longValue();
		}

		for(TransactionOutput out : tx.getOutputs()) {
			outputSum += out.getValue().longValue();
		}

		return Coin.valueOf(inputSum - outputSum);
	}

	public Coin getMicroPaymentPotValue() {
		return getAllPotOutputs().stream().map(TransactionOutput::getValue).reduce(Coin.ZERO, Coin::add);
	}

	private List<TransactionOutput> getAllPotOutputs() {
		Address microPaymentPotAddress = appConfig.getMicroPaymentPotPrivKey().toAddress(appConfig.getNetworkParameters());

		return walletService.getAllSpendCandidates().stream().filter(output -> {
			Address address = output.getAddressFromP2PKHScript(appConfig.getNetworkParameters());
			return Objects.equals(address, microPaymentPotAddress) &&
				output.getParentTransaction().getConfidence().getConfidenceType().equals(TransactionConfidence
					.ConfidenceType.BUILDING) && output.getParentTransactionDepthInBlocks() >= appConfig.getMinConf();
		}).collect(Collectors.toList());
	}

	/***
	 * Add a listener for when a transaction we are watching's confidence
	 * changed due to a new block.
	 *
	 * After the transaction is {bitcoin.minconf} blocks deep, we remove the tx
	 * from the database, as it is considered safe.
	 *
	 * The method should only be called after complete download of the
	 * blockchain, since the handler is called for every block and transaction
	 * we are watching, which will result in high CPU and memory consumption and
	 * might exceed the JVM memory limit. After download is complete, blocks
	 * arrive only sporadically and this is not a problem.
	 */
	private void unlockAccountsOnMinedChannelTransactions() {
		walletService.addConfidenceChangedListener((wallet, tx) -> {
			if (tx.getConfidence().getDepthInBlocks() >= appConfig.getMinConf()) {
				// Check for mined micro payment transactions and unlock account if the pending transaction
				// is seen in a block at least {bitcoin.minconf} deep.
				accountService.getPendingAccounts().stream().filter(account -> {
					Transaction pending = new Transaction(appConfig.getNetworkParameters(),
						account.getChannelTransaction());
					// Use of tracking hash to avoid malleability issues. TxID might have changed since broadcasting.
					return CoinUtils.trackingHash(tx).equals(CoinUtils.trackingHash(pending));
				}).findAny().ifPresent(account -> {
					this.unlockAccount(ECKey.fromPublicOnly(account.clientPublicKey()));
				});
			}
		});
	}

	/***
	 * Finds value of the outputs for the given public key in the transaction.
	 * @param txBytes The serialized transaction or null
	 * @param publicKey The public key of the address
	 * @return Coin value of the sum of outputs that match or Coin.ZERO if none found, or txBytes is null
	 */
	private Coin getOutputForP2PK(@Nullable byte[] txBytes, ECKey publicKey) {
		if (txBytes == null)
			return Coin.ZERO;
		final Transaction tx = new Transaction(appConfig.getNetworkParameters(), txBytes);
		return getOutputForP2PK(tx, publicKey);
	}

	/***
	 * Finds value of first output for the given public key in the transaction.
	 * @param tx The transaction to search
	 * @param publicKey The public key of the address
	 * @return Coin value of the first matching output or Coin.ZERO if none found
	 */
	private Coin getOutputForP2PK(Transaction tx, ECKey publicKey) {
		final Address address = publicKey.toAddress(appConfig.getNetworkParameters());
		Coin value = Coin.ZERO;

		for(int i=0; i<tx.getOutputs().size(); i++) {
			if (Objects.equals(tx.getOutput(i).getAddressFromP2PKHScript(appConfig.getNetworkParameters()), address))
				value = value.plus(tx.getOutput(i).getValue());
		}
		return value;
	}

}
