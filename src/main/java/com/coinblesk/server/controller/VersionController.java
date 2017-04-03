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
package com.coinblesk.server.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON_UTF8_VALUE;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

import java.time.Duration;
import java.time.Instant;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.coinblesk.bitcoin.BitcoinNet;
import com.coinblesk.json.v1.Type;
import com.coinblesk.json.v1.VersionTO;
import com.coinblesk.server.config.AppConfig;
import com.coinblesk.server.utils.ApiVersion;

/**
 * @author Andreas Albrecht
 */
@RestController
@RequestMapping(value = "/version")
@ApiVersion({ "v1" })
public class VersionController {

	private final static Logger LOG = LoggerFactory.getLogger(VersionController.class);

	@Autowired
	private AppConfig appConfig;

	@RequestMapping(
			value = "",
			method = POST,
			consumes = APPLICATION_JSON_UTF8_VALUE,
			produces = APPLICATION_JSON_UTF8_VALUE)
	@ResponseBody
	public VersionTO version(@RequestBody VersionTO input) {
		final String tag = "{version}";
		final Instant startTime = Instant.now();

		try {
			final BitcoinNet serverNetwork = appConfig.getBitcoinNet();
			final String clientVersion = input.clientVersion();
			final BitcoinNet clientNetwork = input.bitcoinNet();

			if (clientVersion == null || clientVersion.isEmpty() || clientNetwork == null) {
				return new VersionTO().type(Type.INPUT_MISMATCH);
			}

			final boolean isSupported = isVersionSupported(clientVersion) && isNetworkSupported(clientNetwork);
			LOG.debug("{} - serverNetwork={}, clientVersion={}, clientNetwork={}, isSupported={}",
					tag, serverNetwork, clientVersion, clientNetwork, isSupported);

			return new VersionTO().bitcoinNet(serverNetwork).setSupported(isSupported).setSuccess();
		} catch (Exception e) {
			LOG.error("{} - failed with exception: ", tag, e);
			return new VersionTO().type(Type.SERVER_ERROR).message(e.getMessage());
		} finally {
			LOG.debug("{} - finished in {} ms", tag, Duration.between(startTime, Instant.now()).toMillis());
		}
	}

	private boolean isNetworkSupported(BitcoinNet clientNetwork) {
		return clientNetwork != null && clientNetwork.equals(appConfig.getBitcoinNet());
	}

	private boolean isVersionSupported(String clientVersion) {
		return appConfig.getSupportedClientVersions().contains(clientVersion);
	}
}
