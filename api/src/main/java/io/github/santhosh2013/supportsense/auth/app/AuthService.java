package io.github.santhosh2013.supportsense.auth.app;

import io.github.santhosh2013.supportsense.auth.persistence.User;
import io.github.santhosh2013.supportsense.auth.persistence.UserRepository;
import io.github.santhosh2013.supportsense.auth.persistence.UserRole;
import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.LoginRequest;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.auth.web.UserResponse;
import io.github.santhosh2013.supportsense.ticket.persistence.Team;
import io.github.santhosh2013.supportsense.ticket.persistence.TeamRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            UserRepository userRepository,
            TeamRepository teamRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtTokenService jwtTokenService,
            RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        Team team = request.teamId() == null
                ? null
                : teamRepository.findById(request.teamId()).orElse(null);

        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.fullName(),
                UserRole.AGENT,
                team);

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Race: two concurrent registrations for the same email. The unique index wins.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (org.springframework.security.core.AuthenticationException e) {
            // Deliberately generic — never reveal whether the account exists.
            throw new BadCredentialsException("Invalid credentials");
        }

        User user = userRepository
                .findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(String presentedRawToken) {
        RefreshTokenService.RotationResult result = refreshTokenService.rotate(presentedRawToken);
        if (!result.accepted()) {
            throw new BadCredentialsException("Invalid or reused refresh token");
        }

        User user = result.user();
        return new AuthResponse(jwtTokenService.generateAccessToken(user), result.newRawToken(), toResponse(user));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(String email) {
        User user = userRepository
                .findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return toResponse(user);
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtTokenService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issueNewFamily(user);
        return new AuthResponse(accessToken, refreshToken, toResponse(user));
    }

    private UserResponse toResponse(User user) {
        Long teamId = user.getTeam() == null ? null : user.getTeam().getId();
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole().name(), teamId);
    }
}
