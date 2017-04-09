package com.coinblesk.server.service;

import com.coinblesk.server.config.AppConfig;
import com.coinblesk.server.dao.AccountRepository;
import com.coinblesk.server.dao.TimeLockedAddressRepository;
import com.coinblesk.server.entity.Account;
import com.coinblesk.server.entity.TimeLockedAddressEntity;
import com.coinblesk.server.exceptions.*;
import com.coinblesk.server.utils.DTOUtils;
import com.coinblesk.util.InsufficientFunds;
import lombok.Data;
import lombok.NonNull;
import org.bitcoinj.core.*;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MicropaymentService {
	private final static Logger LOG = LoggerFactory.getLogger(WalletService.class);

	private final AccountRepository accountRepository;

	private final TimeLockedAddressRepository timeLockedAddressRepository;

	private final AppConfig appConfig;

	private final WalletService walletService;

	private final AccountService accountService;

	private final Duration MINIMUM_LOCKTIME_DURATION = Duration.ofHours(24);

	@Autowired
	public MicropaymentService(AccountRepository accountRepository, TimeLockedAddressRepository timeLockedAddressRepository, AppConfig appConfig, WalletService walletService, AccountService accountService) {
		this.accountRepository = accountRepository;
		this.timeLockedAddressRepository = timeLockedAddressRepository;
		this.appConfig = appConfig;
		this.walletService = walletService;
		this.accountService = accountService;
	}

	@Transactional(isolation = Isolation.SERIALIZABLE)
	@Retryable(TransientDataAccessException.class)
	public VirtualPaymentResult virtualPayment(@NonNull ECKey keySender, @NonNull ECKey keyReceiver, long amount, long requestNonce)
			throws InvalidNonceException, UserNotFoundException, InvalidAmountException, InsufficientFunds, InvalidRequestException {

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
		return new VirtualPaymentResult(
				sender.virtualBalance(),
				sender.serverPrivateKey(),
				receiver.virtualBalance(),
				receiver.serverPrivateKey()
		);
	}

	/**
	 * Result of a successful virtual payment.
	 * Contains the new balances and both private keys of the server.
	 * These can be used to sign the resulting information.
	 */
	public @Data class VirtualPaymentResult {
		private final long newBalanceSender;
		private final byte[] serverPrivateKeyForSender;

		private final long newBalanceReceiver;
		private final byte[] serverPrivateKeyForReceiver;
	}

	@Transactional(isolation = Isolation.SERIALIZABLE)
	public void microPayment(ECKey senderPublicKey, ECKey receiverPublicKey, String txInHex, Long amount, Long nonce) {

		// Parse the transaction
		byte[] txInByes = DTOUtils.fromHex(txInHex);
		final Transaction tx;
		tx = new Transaction(appConfig.getNetworkParameters(), txInByes);
		tx.verify(); // Checks for no input or outputs and no negative values.

		// 1: Validating all inputs
		// 1.1 Make sure all the UTXOs are known to the wallet
		List<TransactionOutput> spentOutputs = tx.getInputs().stream().map(walletService::findOutputFor)
			.collect(Collectors.toList());
		if (spentOutputs.stream().anyMatch(Objects::isNull)) {
			throw new RuntimeException("Transaction spends unknown UTXOs");
		}

		// 1.2 All outputs must be spendable and at least 1 block deep
		spentOutputs.forEach(transactionOutput -> {
			if (!transactionOutput.isAvailableForSpending()) {
				throw new RuntimeException("Input is already spent");
			}

			if (transactionOutput.getParentTransactionDepthInBlocks() < 1) {
				throw new RuntimeException("UTXO must be mined");
			}
		});

		// 1.3 Make sure all inputs are from P2SH addresses
		Set<Address> spentAddresses = spentOutputs.stream()
			.map(transactionOutput -> transactionOutput.getAddressFromP2SH(appConfig.getNetworkParameters()))
			.collect(Collectors.toSet());
		if (spentAddresses.contains(null)) {
			throw new RuntimeException("Transaction must spent P2SH addresses");
		}

		// 1.4 Make sure all inputs come from known time locked addresses and from a single account
		List<byte[]> addressHashes = spentAddresses.stream()
			.map(Address::getHash160)
			.collect(Collectors.toList());
		List<TimeLockedAddressEntity> addresses = timeLockedAddressRepository.findByAddressHashIn(addressHashes);
		if (addresses.size() != spentAddresses.size()) {
			throw new RuntimeException("Used TLA inputs are not known to server");
		}
		Map<Account, List<TimeLockedAddressEntity>> inputAccounts = addresses.stream()
			.collect(Collectors.groupingBy(TimeLockedAddressEntity::getAccount));
		if (inputAccounts.isEmpty()) {
			throw new RuntimeException("Used TLA inputs are not known to server");
		} else if(inputAccounts.size() != 1) {
			throw new RuntimeException("Inputs must be from one account");
		}

		// 1.5 Make sure that owner of inputs is the same as in the request, that signed the DTO
		// 	   (sender, signer and owner of inputs must be equal)
		Account accountSender = inputAccounts.keySet().iterator().next();
		if (!ECKey.fromPublicOnly(accountSender.clientPublicKey()).equals(senderPublicKey)) {
			throw new RuntimeException("Request was not signed by owner of inputs");
		}

		// 1.6 All time locked addresses used must still be locked for some time
		final List<TimeLockedAddressEntity> usedAddresses = inputAccounts.values().iterator().next();
		final boolean allInputsLocked = usedAddresses.stream().allMatch(tla ->
			Instant.ofEpochSecond(tla.getLockTime()).isAfter(Instant.now().plus(MINIMUM_LOCKTIME_DURATION)));
		if (!allInputsLocked) {
			throw new RuntimeException("Inputs must be locked for 24 hours");
		}

		// 1.7 Inputs must be correctly partially signed by sender
		for (int i=0; i<tx.getInputs().size(); i++) {
			TransactionInput input = tx.getInput(i);
			Script spendingScript = input.getScriptSig();
			if (spendingScript.getChunks().size() == 0) {
				throw new RuntimeException("Input was not signed");
			}
			if (spendingScript.getChunks().size() != 1 && !spendingScript.getChunks().get(0).isPushData()) {
				throw new RuntimeException("Signature for input had wrong format");
			}

			// Calculate server signature
			Script scriptPubKey = walletService.findOutputFor(input).getScriptPubKey();
			byte[] connectedAddressHash = scriptPubKey.getPubKeyHash();
			byte[] redeemScript = usedAddresses.stream()
				.filter( tla -> Arrays.equals(tla.getAddressHash(), connectedAddressHash) )
				.findAny().get().getRedeemScript();
			final ECKey serverPrivateKey = ECKey.fromPrivateAndPrecalculatedPublic(accountSender.serverPrivateKey(),
				accountSender.serverPublicKey());
			TransactionSignature serverSig = tx.calculateSignature(i, serverPrivateKey, redeemScript, SigHash.ALL, false);

			// Append server signature and rest of script to check if it makes it spendable
			Script finalSig  = new ScriptBuilder(spendingScript)
				.data(serverSig.encodeToBitcoin())
				.smallNum(1)
				.data(redeemScript)
				.build();
			input.setScriptSig(finalSig);
			finalSig.correctlySpends(tx, i, scriptPubKey, Script.ALL_VERIFY_FLAGS);
		}

		// 2 Check all outputs
		final Set<TransactionOutput> remainingOutputs = new HashSet<>(tx.getOutputs());

		// 2.1) Transaction must have one and only one output to the server pot
		final ECKey serverPubKey = ECKey.fromPublicOnly(accountSender.serverPublicKey());
		final Address serverPot = serverPubKey.toAddress(appConfig.getNetworkParameters());
		final List<TransactionOutput> outputsForServer = remainingOutputs.stream()
			.filter(output -> {
					Address p2PKAddress = output.getAddressFromP2PKHScript(appConfig.getNetworkParameters());
					return (p2PKAddress != null && p2PKAddress.equals(serverPot));
			})
			.collect(Collectors.toList());
		if (outputsForServer.size() != 1) {
			throw new RuntimeException("Transaction must have exactly one output for server");
		}
		final TransactionOutput outputForServer = outputsForServer.get(0);
		if (!remainingOutputs.remove(outputForServer)) {
			throw new CoinbleskInternalError("Could not remove server output from set");
		}

		// 2.2) Transaction must have at most one change output back to sender, which is the newest TLA
		final Address expectedChangeAddress = Address.fromP2SHHash(appConfig.getNetworkParameters(),
			accountSender.latestTimeLockedAddresses().getAddressHash());
		List<TransactionOutput> changeOutputs = remainingOutputs.stream()
			.filter(output -> {
				Address p2SHAddress = output.getAddressFromP2SH(appConfig.getNetworkParameters());
				return (p2SHAddress != null && p2SHAddress.equals(expectedChangeAddress));
			})
			.collect(Collectors.toList());
		if (changeOutputs.size() == 0) {
			LOG.warn("Client provided no change in micropayment");
		} else if (changeOutputs.size() == 1) {
			final long lockTimeOfChange = accountSender.latestTimeLockedAddresses().getLockTime();
			if (Instant.ofEpochSecond(lockTimeOfChange).isBefore(Instant.now().plus(MINIMUM_LOCKTIME_DURATION))) {
				throw new RuntimeException("Change cannot be send to address that is locked for less than " +
					"24 hours");
			}
			final TransactionOutput changeOutput = changeOutputs.get(0);
			if (!remainingOutputs.remove(changeOutput)) {
				throw new CoinbleskInternalError("Could not remove change output from set");
			}
		}
		else if (changeOutputs.size() > 1) {
			throw new RuntimeException("Cannot have multiple change outputs");
		}

		// 2.3) No other outputs are allowed (i.e. sending to an external address) since this would require closing
		//      the transaction, which is not yet supported.
		if (!remainingOutputs.isEmpty()) {
			throw new RuntimeException("Sending to external addresses is not yet supported");
		}

		// 3) Check receiver
		// 3.1) Make sure the receiving public key is known to the server
		final Account accountReceiver = accountService.getByClientPublicKey(receiverPublicKey.getPubKey());
		if (accountReceiver == null) {
			throw new RuntimeException("Receiver is unknown to server");
		}
		// 3.2) Sending to oneself is not allowed
		if (accountReceiver.equals(accountSender)) {
			throw new RuntimeException("Sender and receiver must be different");
		}

		// 4) Check pending channel rules
		// 4.1) Channel must not be locked (i.e. the channel is being closed);
		if (accountSender.isLocked()) {
			throw new RuntimeException("Channel is locked");
		}

		// 4.2) Nonce must be valid
		if (accountSender.nonce() >= nonce) {
			throw new RuntimeException("Invalid nonce");
		}
	}
}
