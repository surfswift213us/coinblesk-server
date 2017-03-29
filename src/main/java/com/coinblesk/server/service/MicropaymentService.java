package com.coinblesk.server.service;

import com.coinblesk.server.dao.KeyRepository;
import com.coinblesk.server.entity.Keys;
import com.coinblesk.server.exceptions.InvalidAmountException;
import com.coinblesk.server.exceptions.InvalidNonceException;
import com.coinblesk.server.exceptions.InvalidRequestException;
import com.coinblesk.server.exceptions.UserNotFoundException;
import com.coinblesk.util.InsufficientFunds;
import lombok.Data;
import lombok.NonNull;
import org.bitcoinj.core.ECKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MicropaymentService {
	private final KeyRepository keyRepository;

	@Autowired
	public MicropaymentService(KeyRepository keyRepository) {
		this.keyRepository = keyRepository;
	}

	@Transactional(isolation = Isolation.SERIALIZABLE)
	@Retryable(TransientDataAccessException.class)
	public VirtualPaymentResult virtualPayment(@NonNull ECKey keySender, @NonNull ECKey keyReceiver, long amount, long requestNonce)
			throws InvalidNonceException, UserNotFoundException, InvalidAmountException, InsufficientFunds, InvalidRequestException {

		// Sender and receiver must be different entities
		if (keySender.getPublicKeyAsHex().equals(keyReceiver.getPublicKeyAsHex()))
			throw new InvalidRequestException("The sender and receiver cannot be the same entities");

		final Keys sender = keyRepository.findByClientPublicKey(keySender.getPubKey());
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
		final Keys receiver = keyRepository.findByClientPublicKey(keyReceiver.getPubKey());
		if (receiver == null)
			throw new UserNotFoundException(keyReceiver.getPublicKeyAsHex());

		// Do the transfer
		final long senderOldBalance = sender.virtualBalance();
		final long receiverOldBalance = receiver.virtualBalance();
		sender.virtualBalance(senderOldBalance - amount);
		receiver.virtualBalance(receiverOldBalance + amount);

		// Guarantee that this request is only processed once
		sender.nonce(requestNonce);

		keyRepository.save(sender);
		keyRepository.save(receiver);

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
}
