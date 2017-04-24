package com.coinblesk.server.enumerator;

public enum EventType {
	EVENT_SERVICE_AUTO_REMOVAL,
	EVENT_SERVICE_EMERGENCY_EMAIL_SENT,
	USER_ACCOUNT_LOGIN_FAILED,
	USER_ACCOUNT_LOGIN_FAILED_WITH_DELETED_ACCOUNT,
	USER_ACCOUNT_COULD_NOT_CREATE_USER,
	USER_ACCOUNT_CREATE_TOKEN_COULD_NOT_BE_SENT,
	USER_ACCOUNT_COULD_NOT_BE_PROVIDED,
	USER_ACCOUNT_COULD_NOT_BE_ACTIVATED_WRONG_LINK,
	USER_ACCOUNT_COULD_NOT_BE_DELETED,
	USER_ACCOUNT_COULD_NOT_VERIFY_FORGOT_WRONG_LINK,
	USER_ACCOUNT_COULD_NOT_HANDLE_FORGET_REQUEST,
	USER_ACCOUNT_COULD_NOT_SEND_FORGET_EMAIL,
	USER_ACCOUNT_PASSWORD_COULD_NOT_BE_CHANGED,
	USER_ACCOUNT_COULD_NOT_TRANSFER_P2SH
}
