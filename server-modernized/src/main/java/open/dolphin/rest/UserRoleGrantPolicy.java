package open.dolphin.rest;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ユーザーへ付与できるロールの許可リスト。
 * <p>
 * 施設管理者は施設内ロール（admin / doctor / nurse など）のみ付与・剥奪できる。
 * system-administrator 系ロールの付与・剥奪はシステム管理者のみ。
 * 許可リストに無いロールの新規付与は誰にもできない（既存ユーザーが既に持っているものはそのまま残せる）。
 */
final class UserRoleGrantPolicy {

    static final Set<String> FACILITY_GRANTABLE_ROLES = Set.of(
            "user", "admin", "doctor", "nurse", "reception", "clerk", "office");

    static final Set<String> SYSTEM_ADMIN_ROLES = Set.of(
            "system_admin", "system-admin", "system-administrator", "system_administrator");

    enum Violation {
        NONE,
        SYSTEM_ADMIN_ROLE_REQUIRES_SYSTEM_ADMIN,
        ROLE_NOT_ALLOWED
    }

    private UserRoleGrantPolicy() {
    }

    /**
     * @param currentRoles   対象ユーザーが現在持っているロール（新規作成時は空）
     * @param requestedRoles 要求されたロール
     * @param actorIsSystemAdmin 操作者がシステム管理者か
     */
    static Violation check(Collection<String> currentRoles, Collection<String> requestedRoles, boolean actorIsSystemAdmin) {
        Set<String> current = normalize(currentRoles);
        Set<String> requested = normalize(requestedRoles);

        for (String role : requested) {
            if (current.contains(role)) {
                continue;
            }
            if (SYSTEM_ADMIN_ROLES.contains(role)) {
                if (!actorIsSystemAdmin) {
                    return Violation.SYSTEM_ADMIN_ROLE_REQUIRES_SYSTEM_ADMIN;
                }
                continue;
            }
            if (!FACILITY_GRANTABLE_ROLES.contains(role)) {
                return Violation.ROLE_NOT_ALLOWED;
            }
        }
        if (!actorIsSystemAdmin) {
            for (String role : current) {
                if (SYSTEM_ADMIN_ROLES.contains(role) && !requested.contains(role)) {
                    return Violation.SYSTEM_ADMIN_ROLE_REQUIRES_SYSTEM_ADMIN;
                }
            }
        }
        return Violation.NONE;
    }

    static boolean containsSystemAdminRole(Collection<String> roles) {
        for (String role : normalize(roles)) {
            if (SYSTEM_ADMIN_ROLES.contains(role)) {
                return true;
            }
        }
        return false;
    }

    static Set<String> normalize(Collection<String> roles) {
        Set<String> normalized = new LinkedHashSet<>();
        if (roles == null) {
            return normalized;
        }
        for (String role : roles) {
            if (role == null) {
                continue;
            }
            String value = role.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return normalized;
    }
}
