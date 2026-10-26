package com.ragqa.service;

import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountPasswordServiceTest {
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final CurrentUserService current = mock(CurrentUserService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthenticatedUserPrincipal identity = new AuthenticatedUserPrincipal(25, 3);
    private final AccountPasswordService service = new AccountPasswordService(users, current, encoder);
    @BeforeEach void setup() {
        when(current.requireUserId(identity)).thenReturn(25L);
        when(users.currentPasswordHash(25, 3)).thenReturn("stored-hash");
        when(encoder.matches("old-password-123", "stored-hash")).thenReturn(true);
        when(encoder.encode("new-password-123")).thenReturn("new-hash");
        when(users.changePassword(25, 3, "new-hash")).thenReturn(1);
    }
    @Test void changesOnlyOwnAccountThroughVersionCasAndHashesNewPassword() {
        service.change(identity, "old-password-123", "new-password-123");
        verify(users).changePassword(25, 3, "new-hash");
        verify(current, times(2)).requireUserId(identity);
        assertThatThrownBy(() -> service.change(identity, "old-password-123", "new-password-123")).isInstanceOf(BusinessException.class);
        verify(users, times(1)).changePassword(anyLong(), anyLong(), anyString());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"short", "old-password-123", "new\npassword-123", "密密密密密密密密密密密密密密密密密密密密密密密密密"})
    void invalidPasswordDoesNotReadHashOrWrite(String password) {
        assertThatThrownBy(() -> service.change(identity, "old-password-123", password)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(users, encoder);
    }
    @Test void wrongOldPasswordCannotWriteOrEncodeNewOne() {
        assertThatThrownBy(() -> service.change(identity, "wrong", "new-password-123")).isInstanceOf(BusinessException.class);
        verify(encoder, never()).encode(anyString()); verify(users, never()).changePassword(anyLong(), anyLong(), anyString());
    }
    @Test void concurrentRevokeOrPasswordChangeWinsRatherThanBeingOverwritten() {
        when(users.changePassword(25, 3, "new-hash")).thenReturn(0);
        assertThatThrownBy(() -> service.change(identity, "old-password-123", "new-password-123"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
    }
}
