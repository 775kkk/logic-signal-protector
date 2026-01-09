package com.logicsignalprotector.commandcenter.domain;

public enum ChatState {
  NONE,
  AWAIT_LOGIN_CREDENTIALS,
  AWAIT_REGISTER_CREDENTIALS,
  AWAIT_LOGOUT_CONFIRM,
  AWAIT_USER_HARD_DELETE_CONFIRM
}
