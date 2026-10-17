package com.runevault.banking;

/** Only use when the provider definitively confirms that no transfer occurred. */
public class BankRejectedException extends RuntimeException {
    public BankRejectedException(String message) { super(message); }
}
