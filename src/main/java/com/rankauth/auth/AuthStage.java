package com.rankauth.auth;

public enum AuthStage {
    /** Brand-new player, needs to set a password. */
    AWAITING_PASSWORD,
    /** Password just entered once, needs to be repeated. */
    AWAITING_PASSWORD_CONFIRM,
    /** Password confirmed, needs an email address. */
    AWAITING_EMAIL,
    /** Email submitted, needs the 6-digit code. */
    AWAITING_CODE,
    /** Registered previously, needs to run /login. */
    AWAITING_LOGIN,
    /** Fully authenticated, unrestricted. */
    AUTHENTICATED
}
