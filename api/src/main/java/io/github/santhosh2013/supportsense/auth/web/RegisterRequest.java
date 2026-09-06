package io.github.santhosh2013.supportsense.auth.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank String fullName,
        Long teamId) {

    // Public self-registration must never grant team membership — team scoping is
    // security-relevant (BR-A10), so silently ignoring a bad teamId is not an option; a
    // client that thinks it joined a team must be told loudly that it did not.
    @AssertTrue(message = "teamId must be null on public self-registration")
    public boolean isTeamIdAbsent() {
        return teamId == null;
    }
}
