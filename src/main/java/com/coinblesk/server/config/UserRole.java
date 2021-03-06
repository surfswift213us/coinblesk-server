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
package com.coinblesk.server.config;

import org.springframework.security.core.GrantedAuthority;

/**
 * @author Andreas Albrecht
 * @author Thomas Bocek
 */
public enum UserRole implements GrantedAuthority {
	// Note: no "ROLE_" prefix.
	USER, ADMIN;

	private static final long serialVersionUID = 13449904136869644L;

	public static final String ROLE_USER = "ROLE_USER";
	public static final String ROLE_ADMIN = "ROLE_ADMIN";

	@Override
	public String getAuthority() {
		// Spring convention: authority starts with "ROLE_".
		return String.format("ROLE_%s", name());
	}

}
