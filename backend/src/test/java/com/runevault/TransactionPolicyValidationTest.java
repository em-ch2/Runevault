package com.runevault;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransactionPolicyValidationTest {
 @Test void rejectsInvalidPolicyConfiguration() {
  try (var factory = Validation.buildDefaultValidatorFactory()) {
   var validator = factory.getValidator();
   assertTrue(validator.validate(new TransactionPolicy(50000, List.of("acct-999"), 2)).isEmpty());
   assertFalse(validator.validate(new TransactionPolicy(0, List.of("acct-999"), 2)).isEmpty());
   assertFalse(validator.validate(new TransactionPolicy(100000001, List.of("acct-999"), 2)).isEmpty());
   assertFalse(validator.validate(new TransactionPolicy(50000, List.of(), 2)).isEmpty());
   assertFalse(validator.validate(new TransactionPolicy(50000, List.of("bad account"), 2)).isEmpty());
   assertFalse(validator.validate(new TransactionPolicy(50000, List.of("acct-999"), 0)).isEmpty());
  }
 }
}
