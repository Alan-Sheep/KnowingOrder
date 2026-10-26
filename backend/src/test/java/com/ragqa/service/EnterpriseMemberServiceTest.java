package com.ragqa.service;

import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.EnterpriseDirectoryMapper;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 故障与竞态注入：验证服务不能把存储错误或被更换的认证版本当成有效身份。 */
class EnterpriseMemberServiceTest {
    private final CurrentUserService users = mock(CurrentUserService.class);
    private final EnterpriseDirectoryMapper directory = mock(EnterpriseDirectoryMapper.class);
    private final EnterpriseMemberService service = new EnterpriseMemberServiceImpl(users, directory);
    private final AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(1, 1);

    @Test
    void workspaceDiscoveryRechecksIdentityAndNeverHidesDatabaseFailure() {
        when(users.requireUserId(principal)).thenReturn(1L);
        var failure = new DataAccessResourceFailureException("虚构企业目录故障");
        when(directory.listActiveWorkspaces(1, 1)).thenThrow(failure);
        assertThatThrownBy(() -> service.listWorkspaces(principal)).isSameAs(failure);
        org.mockito.Mockito.doReturn(java.util.List.of()).when(directory).listActiveWorkspaces(1, 1);
        when(users.requireUserId(principal)).thenReturn(1L).thenThrow(new BusinessException(com.ragqa.common.ResultCode.UNAUTHORIZED));
        assertThatThrownBy(() -> service.listWorkspaces(principal)).isInstanceOf(BusinessException.class);
    }

    @Test
    void workspaceOverflowDoesNotReturnAnIncompleteSelection() {
        when(users.requireUserId(principal)).thenReturn(1L);
        when(directory.listActiveWorkspaces(1, 1)).thenReturn(java.util.Collections.nCopies(101,
                new EnterpriseMemberService.Workspace(1L, "虚构企业", "EMPLOYEE")));
        assertThatThrownBy(() -> service.listWorkspaces(principal)).isInstanceOf(BusinessException.class);
    }

    @Test
    void revalidationNeverUpgradesRevokedPrincipalToNewAuthVersion() {
        when(users.requireUserId(principal)).thenReturn(1L);
        when(directory.findActiveMember(1, 10, 1)).thenReturn(
                new EnterpriseMemberContext(10, 101, 1, null, "EMPLOYEE", 1, 1, 2));
        assertThatThrownBy(() -> service.requireMember(principal, 10L)).isInstanceOf(BusinessException.class);
        verify(directory).findActiveMember(1, 10, 1);
    }

    @Test
    void anotherUserOrEnterpriseCannotReplacePinnedScope() {
        when(users.requireUserId(principal)).thenReturn(1L);
        when(directory.findActiveMember(1, 10, 1)).thenReturn(
                new EnterpriseMemberContext(11, 101, 2, null, "EMPLOYEE", 1, 1, 1));
        assertThatThrownBy(() -> service.requireMember(principal, 10L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void databaseFailureIsNotConvertedToMissingResourceOrDefaultMember() {
        when(users.requireUserId(principal)).thenReturn(1L);
        var failure = new DataAccessResourceFailureException("虚构企业目录故障");
        when(directory.findActiveMember(1, 10, 1)).thenThrow(failure);
        assertThatThrownBy(() -> service.requireMember(principal, 10L)).isSameAs(failure);
    }

    @Test
    void missingAsyncContextCannotReachDirectory() {
        assertThatThrownBy(() -> service.requireCurrent(null)).isInstanceOf(BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(users, directory);
    }
}
