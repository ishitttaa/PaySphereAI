package com.paysphere.merchant;

import com.paysphere.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** N9 step 1: the merchants table and users.merchant_id exist with the right rules. */
class MerchantSchemaIntegrationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private String uniqueName() {
        return "Merchant " + UUID.randomUUID();
    }

    @Test
    void aMerchantCanBeCreatedAndDefaultsToActive() {
        String name = uniqueName();
        jdbc.update("insert into merchants (name) values (?)", name);

        assertThat(jdbc.queryForObject(
                "select status from merchants where name = ?", String.class, name))
                .isEqualTo("ACTIVE");
    }

    @Test
    void duplicateMerchantNamesAreRejected() {
        String name = uniqueName();
        jdbc.update("insert into merchants (name) values (?)", name);

        assertThatThrownBy(() -> jdbc.update("insert into merchants (name) values (?)", name))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anInvalidMerchantStatusIsRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into merchants (name, status) values (?, 'DELETED')", uniqueName()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usersHaveAnOptionalMerchantIdThatPointsToMerchants() {
        assertThat(jdbc.queryForObject(
                "select is_nullable from information_schema.columns "
                        + "where table_name = 'users' and column_name = 'merchant_id'",
                String.class)).isEqualTo("YES");

        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints c "
                        + "join information_schema.key_column_usage k on c.constraint_name = k.constraint_name "
                        + "where c.table_name = 'users' and c.constraint_type = 'FOREIGN KEY' "
                        + "and k.column_name = 'merchant_id'",
                Integer.class)).isGreaterThanOrEqualTo(1);
    }
}