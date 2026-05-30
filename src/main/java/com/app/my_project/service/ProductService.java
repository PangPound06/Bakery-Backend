package com.app.my_project.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.my_project.models.ProductModel;
import com.app.my_project.models.ProductRequest;

/**
 * Data-access logic สำหรับสินค้า (แยกออกมาจาก ProductController)
 * รวม raw JDBC ทั้งหมดไว้ที่นี่ ทำให้ controller บางและทดสอบ unit ได้ง่าย
 * เมื่อเกิด SQLException จะ wrap เป็น RuntimeException เพื่อให้ชั้น controller
 * แปลงเป็น HTTP 500 ได้สม่ำเสมอ
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final DataSource dataSource;

    public ProductService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ✅ SQL SELECT พื้นฐานที่ JOIN กับ tb_categories
    private static final String BASE_SELECT = "SELECT p.id, p.name, p.price, p.category_id, p.image, p.type, p.description, "
            +
            "p.\"stockQuantity\", p.\"isAvailable\", p.options, " +
            "c.slug AS cat_slug, c.name AS cat_name, c.icon AS cat_icon " +
            "FROM tb_products p " +
            "LEFT JOIN tb_categories c ON p.category_id = c.id ";

    // ✅ Helper: สร้าง ProductModel จาก ResultSet
    private ProductModel mapProduct(ResultSet rs) throws SQLException {
        return new ProductModel(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getDouble("price"),
                rs.getString("cat_slug"),
                rs.getLong("category_id"),
                rs.getString("cat_name"),
                rs.getString("cat_icon"),
                rs.getString("image"),
                rs.getString("type"),
                rs.getString("description"),
                rs.getLong("stockQuantity"),
                rs.getBoolean("isAvailable"),
                rs.getString("options"));
    }

    public List<ProductModel> getAll() {
        String sql = BASE_SELECT + "ORDER BY p.id ASC";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            List<ProductModel> products = new ArrayList<>();
            while (rs.next())
                products.add(mapProduct(rs));
            return products;
        } catch (SQLException e) {
            log.error("getAll error", e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public Optional<ProductModel> getById(Long id) {
        String sql = BASE_SELECT + "WHERE p.id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next())
                    return Optional.of(mapProduct(rs));
                return Optional.empty();
            }
        } catch (SQLException e) {
            log.error("getById error", e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public List<ProductModel> getByCategory(String category) {
        String sql = BASE_SELECT + "WHERE LOWER(c.slug) = LOWER(?) ORDER BY p.id ASC";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, category);
            try (ResultSet rs = stmt.executeQuery()) {
                List<ProductModel> products = new ArrayList<>();
                while (rs.next())
                    products.add(mapProduct(rs));
                return products;
            }
        } catch (SQLException e) {
            log.error("getByCategory error", e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public boolean exists(Long id) {
        String sql = "SELECT id FROM tb_products WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("exists error", e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** @return id ของสินค้าที่เพิ่ง insert, หรือ null ถ้าไม่สำเร็จ */
    public Long create(ProductRequest request) {
        try (Connection conn = dataSource.getConnection()) {
            Long categoryId = resolveCategoryId(conn, request);
            String sql = "INSERT INTO tb_products (name, price, category, category_id, image, type, description, " +
                    "\"stockQuantity\", \"isAvailable\", options) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bindProductParams(stmt, request, categoryId);
                int affectedRows = stmt.executeUpdate();
                if (affectedRows > 0) {
                    try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                        if (generatedKeys.next())
                            return generatedKeys.getLong(1);
                    }
                }
                return null;
            }
        } catch (SQLException e) {
            log.error("create error", e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** @return true ถ้ามีแถวถูกแก้ไข */
    public boolean update(Long id, ProductRequest request) {
        try (Connection conn = dataSource.getConnection()) {
            Long categoryId = resolveCategoryId(conn, request);
            String sql = "UPDATE tb_products SET name = ?, price = ?, category = ?, category_id = ?, " +
                    "image = ?, type = ?, description = ?, \"stockQuantity\" = ?, \"isAvailable\" = ?, options = ? " +
                    "WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                bindProductParams(stmt, request, categoryId);
                stmt.setLong(11, id);
                return stmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.error("update error", e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** @return true ถ้ามีแถวถูกลบ */
    public boolean delete(Long id) {
        String sql = "DELETE FROM tb_products WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("delete error", e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    // ── helpers ──────────────────────────────────────────────

    /** หา category_id จาก categoryId ตรงๆ หรือ lookup จาก slug */
    private Long resolveCategoryId(Connection conn, ProductRequest request) throws SQLException {
        Long categoryId = request.getCategoryId();
        if (categoryId == null && request.getCategory() != null) {
            String findCatSql = "SELECT id FROM tb_categories WHERE LOWER(slug) = LOWER(?)";
            try (PreparedStatement catStmt = conn.prepareStatement(findCatSql)) {
                catStmt.setString(1, request.getCategory());
                try (ResultSet catRs = catStmt.executeQuery()) {
                    if (catRs.next())
                        categoryId = catRs.getLong("id");
                }
            }
        }
        return categoryId;
    }

    /** bind พารามิเตอร์ 1..10 ตามลำดับคอลัมน์ (ใช้ร่วมกันทั้ง insert และ update) */
    private void bindProductParams(PreparedStatement stmt, ProductRequest r, Long categoryId) throws SQLException {
        stmt.setString(1, r.getName());
        stmt.setDouble(2, r.getPrice() != null ? r.getPrice() : 0.0);
        stmt.setString(3, r.getCategory() != null ? r.getCategory() : "other");
        if (categoryId != null)
            stmt.setLong(4, categoryId);
        else
            stmt.setNull(4, Types.BIGINT);
        stmt.setString(5, r.getImage() != null ? r.getImage() : "");
        stmt.setString(6, r.getType() != null ? r.getType() : "");
        stmt.setString(7, r.getDescription() != null ? r.getDescription() : "");
        stmt.setLong(8, r.getStockQuantity() != null ? r.getStockQuantity() : 0L);
        stmt.setBoolean(9, r.getIsAvailable() != null ? r.getIsAvailable() : true);
        stmt.setString(10, r.getOptions());
    }
}