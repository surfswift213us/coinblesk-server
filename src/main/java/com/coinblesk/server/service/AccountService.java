/*
 * Copyright 2016 The Coinblesk team and the CSG Group at University of Zurich
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.coinblesk.server.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coinblesk.bitcoin.TimeLockedAddress;
import com.coinblesk.server.config.AppConfig;
import com.coinblesk.server.dao.AccountRepository;
import com.coinblesk.server.dao.TimeLockedAddressRepository;
import com.coinblesk.server.entity.Account;
import com.coinblesk.server.entity.TimeLockedAddressEntity;
import com.coinblesk.server.exceptions.CoinbleskInternalError;
import com.coinblesk.server.exceptions.InvalidLockTimeException;
import com.coinblesk.server.exceptions.UserNotFoundException;
import com.coinblesk.util.DTOUtils;
import com.google.common.annotations.VisibleForTesting;

import lombok.Data;
import lombok.NonNull;

/**
 * @author Thomas Bocek
 * @author Andreas Albrecht
 * @author Sebastian Stephan
 */
@Service
public class AccountService {

	private final AccountRepository accountRepository;

	private final TimeLockedAddressRepository timeLockedAddressRepository;

	private final AppConfig appConfig;

	@Autowired
	public AccountService(@NonNull AccountRepository accountRepository, @NonNull TimeLockedAddressRepository
		timeLockedAddressRepository, AppConfig appConfig) {
		this.accountRepository = accountRepository;
		this.timeLockedAddressRepository = timeLockedAddressRepository;
		this.appConfig = appConfig;
	}

	@Transactional(readOnly = true)
	public Account getByClientPublicKey(@NonNull final byte[] clientPublicKey) {
		return accountRepository.findByClientPublicKey(clientPublicKey);
	}

	/**
	 * Creates a new account for a given client public key.
	 * Returns the public key of the server for the newly created or already existing account.
	 * <p>
	 * Idempodent: Calling this function with a public key that already exists will not make any changes.
	 *
	 * @param clientPublicKey The client public key for which an account should be generated
	 * @return The server ECKey pair associated with that account.
	 */
	@Transactional
	public ECKey createAccount(@NonNull ECKey clientPublicKey) {

		// Check if client has already account
		final Account existingAccount = accountRepository.findByClientPublicKey(clientPublicKey.getPubKey());
		if (existingAccount != null) {
			return ECKey.fromPrivateAndPrecalculatedPublic(existingAccount.serverPrivateKey(), existingAccount
				.serverPublicKey());
		}

		// Not in database => Create new account with new server key pair
		ECKey serverKeyPair = new ECKey();
		final Account clientKey = new Account().clientPublicKey(clientPublicKey.getPubKey()).serverPrivateKey
			(serverKeyPair.getPrivKeyBytes()).serverPublicKey(serverKeyPair.getPubKey()).timeCreated(Instant.now()
			.getEpochSecond());

		final Account newAccount = accountRepository.save(clientKey);

		return ECKey.fromPrivateAndPrecalculatedPublic(newAccount.serverPrivateKey(), newAccount.serverPublicKey());
	}

	@Transactional
	@VisibleForTesting
	public boolean deleteAccount(ECKey publicKey) {
		final Account existingAccount = accountRepository.findByClientPublicKey(publicKey.getPubKey());
		if (existingAccount != null) {
			accountRepository.delete(existingAccount);
			return true;
		}
		return false;
	}

	private void deleteAccount(Account account) {
		accountRepository.delete(account);
	}

	@Transactional(readOnly = true)
	public List<Account> allAccounts() {
		return StreamSupport.stream(accountRepository.findAll().spliterator(), false).collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public List<TimeLockedAddressEntity> allAddresses() {
		return StreamSupport.stream(timeLockedAddressRepository.findAll().spliterator(), false).collect(Collectors
			.toList());
	}

	@Transactional(readOnly = true)
	public List<Account> getPendingAccounts() {
		return accountRepository.findByLockedIsTrue();
	}

	@Transactional
	public CreateTimeLockedAddressResponse createTimeLockedAddress(@NonNull ECKey clientPublicKey, long lockTime)
		throws UserNotFoundException, InvalidLockTimeException {

		// Lock time must be valid
		final long minLockTime = Instant.now().getEpochSecond() + appConfig.getMinimumLockTimeSeconds();
		final long maxLockTime = Instant.now().plus(Duration.ofDays(appConfig.getMaximumLockTimeDays()))
			.getEpochSecond();
		if (lockTime < minLockTime || lockTime > maxLockTime) {
			throw new InvalidLockTimeException();
		}

		// Get client for which a new address should be created
		Account client = accountRepository.findByClientPublicKey(clientPublicKey.getPubKey());
		if (client == null)
			throw new UserNotFoundException(clientPublicKey.getPublicKeyAsHex());

		// Create address
		final TimeLockedAddress address = new TimeLockedAddress(client.clientPublicKey(), client.serverPublicKey(),
			lockTime);

		// Check if address is already in database, if so nothing to do
		TimeLockedAddressEntity existingAddress = timeLockedAddressRepository.findByAddressHash(address.getAddressHash
			());

		ECKey serverPrivateKey = ECKey.fromPrivateAndPrecalculatedPublic(client.serverPrivateKey(), client
			.serverPublicKey());

		if (existingAddress != null) {
			return new CreateTimeLockedAddressResponse(address, serverPrivateKey);
		}

		// Create the new address entity and save
		TimeLockedAddressEntity addressEntity = new TimeLockedAddressEntity();
		addressEntity.setLockTime(address.getLockTime()).setAddressHash(address.getAddressHash()).setRedeemScript
			(address.createRedeemScript().getProgram()).setTimeCreated(Utils.currentTimeSeconds()).setAccount(client);
		timeLockedAddressRepository.save(addressEntity);

		return new CreateTimeLockedAddressResponse(address, serverPrivateKey);
	}

	@Transactional(readOnly = true)
	public GetVirtualBalanceResponse getVirtualBalanceByClientPublicKey(@NonNull byte[] publicKey) throws
		UserNotFoundException {

		if (publicKey.length == 0) {
			throw new IllegalArgumentException("publicKey must not be null");
		}
		Account account = accountRepository.findByClientPublicKey(publicKey);
		if (account == null)
			throw new UserNotFoundException(DTOUtils.toHex(publicKey));

		return new GetVirtualBalanceResponse(account.virtualBalance(), ECKey.fromPrivateAndPrecalculatedPublic(account
			.serverPrivateKey(), account.serverPublicKey()));
	}

	@Transactional
	// this method is used to empty the temporary account's virtual balance and directly delete it afterwards
	// the account A cannot have a timelockedaddress, because it is only used as long as the user has an
	// unregistered token (user who got funds, but is not yet registered). this method is used during the transition
	// from unregistered to registered
	public void moveVirtualBalanceFromAToBAndDeleteA(Account accountA, Account accountB) {

		if (accountA.getTimeLockedAddresses().size() != 0) {
			throw new CoinbleskInternalError("The account has time locked addresses.");
		}

		accountB.virtualBalance(accountA.virtualBalance());
		accountA.virtualBalance(0L);
		deleteAccount(accountA);
	}

	public TimeLockedAddress getTimeLockedAddressByAddressHash(@NonNull byte[] addressHash) {
		TimeLockedAddressEntity entity = timeLockedAddressRepository.findByAddressHash(addressHash);
		return entity == null ? null : TimeLockedAddress.fromRedeemScript(entity.getRedeemScript());
	}

	public long getSumOfAllVirtualBalances() {
		long result = 0L;
		Long dbValue = accountRepository.getSumOfAllVirtualBalances();

		if(dbValue != null) {
			result = dbValue;
		}

		return result;
	}

	@Data
	public static class CreateTimeLockedAddressResponse {
		@NonNull
		final private TimeLockedAddress timeLockedAddress;
		@NonNull
		final private ECKey serverPrivateKey;
	}

	@Data
	public static class GetVirtualBalanceResponse {
		final private long balance;
		@NonNull
		final private ECKey serverPrivateKey;
	}

}
