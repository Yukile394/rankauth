package com.rankauth.auth;

public enum AuthStage {
    /** Brand-new player, needs to run /register <şifre> <email>. */
    AWAITING_REGISTER,
    /** Password + email submitted, needs to run /kod <kod>. */
    AWAITING_CODE,
    /** Registered previously, needs to run /login <şifre>. */
    AWAITING_LOGIN,
    /** Fully authenticated, unrestricted. */
    AUTHENTICATED
}
