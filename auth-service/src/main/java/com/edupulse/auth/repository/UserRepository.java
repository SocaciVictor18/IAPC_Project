package com.edupulse.auth.repository;

import com.edupulse.auth.domain.UserAccount;
import com.edupulse.common.crypto.AesGcmCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class UserRepository {

    private static final String SELECT = """
            select u.id, u.email, u.password_hash, u.full_name_enc, u.org_unit_id, u.clearance_level, u.enabled,
                   ou.code as ou_code, ou.name as ou_name, o.id as org_id, o.name as org_name,
                   coalesce(array_agg(r.role) filter (where r.role is not null), '{}') as roles
            from auth.app_user u
            join auth.org_unit ou on ou.id = u.org_unit_id
            join auth.organization o on o.id = ou.organization_id
            left join auth.user_role r on r.user_id = u.id
            """;
    private static final String GROUP = " group by u.id, ou.id, o.id";

    private final JdbcClient jdbc;
    private final AesGcmCipher cipher;

    public Optional<UserAccount> findByEmail(String email) {
        return jdbc.sql(SELECT + " where lower(u.email) = lower(?)" + GROUP).param(email).query(this::map).optional();
    }

    public Optional<UserAccount> findById(UUID id) {
        return jdbc.sql(SELECT + " where u.id = ?" + GROUP).param(id).query(this::map).optional();
    }

    public void create(String email, String passwordHash, String fullName, UUID orgUnitId, int clearance, String role) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into auth.app_user (id, email, password_hash, full_name_enc, org_unit_id, clearance_level) values (?,?,?,?,?,?)")
                .params(id, email, passwordHash, cipher.encryptString(fullName), orgUnitId, clearance)
                .update();
        jdbc.sql("insert into auth.user_role (user_id, role) values (?, ?)").params(id, role).update();
    }

    private UserAccount map(ResultSet rs, int rowNum) throws SQLException {
        Array arr = rs.getArray("roles");
        Set<String> roles = arr == null ? Set.of() : new HashSet<>(Arrays.asList((String[]) arr.getArray()));
        byte[] nameEnc = rs.getBytes("full_name_enc");
        return new UserAccount(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                nameEnc == null ? null : cipher.decryptString(nameEnc),
                rs.getObject("org_unit_id", UUID.class),
                rs.getString("ou_code"),
                rs.getString("ou_name"),
                rs.getObject("org_id", UUID.class),
                rs.getString("org_name"),
                rs.getInt("clearance_level"),
                rs.getBoolean("enabled"),
                roles);
    }
}
