package com.retail.bidding.vendor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class VendorRepository {

    private final JdbcClient jdbc;

    VendorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Vendor vendor) {
        jdbc.sql("""
                INSERT INTO vendor (id, name, email, phone, status, created_at)
                VALUES (:id, :name, :email, :phone, :status, :createdAt)
                """)
                .param("id", vendor.id())
                .param("name", vendor.name())
                .param("email", vendor.email())
                .param("phone", vendor.phone())
                .param("status", vendor.status().name())
                .param("createdAt", java.sql.Timestamp.from(vendor.createdAt()))
                .update();
    }

    Optional<Vendor> findById(UUID id) {
        return jdbc.sql("SELECT * FROM vendor WHERE id = :id")
                .param("id", id)
                .query(VendorRepository::map)
                .optional();
    }

    List<Vendor> findAll(VendorStatus status) {
        if (status == null) {
            return jdbc.sql("SELECT * FROM vendor ORDER BY created_at").query(VendorRepository::map).list();
        }
        return jdbc.sql("SELECT * FROM vendor WHERE status = :status ORDER BY created_at")
                .param("status", status.name())
                .query(VendorRepository::map)
                .list();
    }

    boolean updateStatus(UUID id, VendorStatus status) {
        return jdbc.sql("UPDATE vendor SET status = :status WHERE id = :id")
                .param("status", status.name())
                .param("id", id)
                .update() == 1;
    }

    private static Vendor map(ResultSet rs, int rowNum) throws SQLException {
        return new Vendor(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("phone"),
                VendorStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }
}
