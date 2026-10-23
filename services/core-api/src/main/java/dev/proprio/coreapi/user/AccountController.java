package dev.proprio.coreapi.user;

import dev.proprio.coreapi.api.AccountApi;
import dev.proprio.coreapi.api.model.Consent;
import dev.proprio.coreapi.api.model.ConsentUpdate;
import dev.proprio.coreapi.api.model.DataExport;
import dev.proprio.coreapi.api.model.User;
import dev.proprio.coreapi.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController implements AccountApi {

  private final AccountService account;
  private final CurrentUser currentUser;

  public AccountController(AccountService account, CurrentUser currentUser) {
    this.account = account;
    this.currentUser = currentUser;
  }

  @Override
  public ResponseEntity<User> getMe() {
    return ResponseEntity.ok(account.me(currentUser.id()));
  }

  @Override
  public ResponseEntity<Void> deleteMe() {
    account.delete(currentUser.id());
    return ResponseEntity.accepted().build();
  }

  @Override
  public ResponseEntity<Consent> setConsent(ConsentUpdate body) {
    return ResponseEntity.ok(account.setConsent(currentUser.id(), body.getTrainingUse()));
  }

  @Override
  public ResponseEntity<DataExport> exportMyData() {
    return ResponseEntity.ok(account.export(currentUser.id()));
  }
}
