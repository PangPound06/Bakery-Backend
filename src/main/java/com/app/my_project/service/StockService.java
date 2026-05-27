package com.app.my_project.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * StockService — wraps stock operations ของ tb_products
 *
 * เหตุผลที่ใช้ JdbcTemplate แทน ProductRepository:
 * 1. ProductEntity (camelCase column) มี issue กับ Hibernate naming strategy
 * → ใช้ raw SQL ตรงๆ ปลอดภัยกว่า
 * 2. Logic ที่ต้องใช้ GREATEST() และ FRESH_STOCK check (9999)
 * → JPQL ทำยาก ใช้ native SQL คุ้มกว่า
 * 3. Stock operations เป็น "side effect" ของ order — ไม่ใช่ entity lifecycle
 *
 * Public methods:
 * - decreaseStock(productId, qty) — ลด stock + อัพเดท isAvailable
 * - increaseStock(productId, qty) — คืน stock + เปิด isAvailable
 * - batchIncreaseStock(items) — สำหรับ cancel order (batch update)
 * - findImage(productId) — ดึงรูปสินค้า (ใช้ใน getOrderById)
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    private static final int FRESH_STOCK_VALUE = 9999;

    private final JdbcTemplate jdbcTemplate;

    public StockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * ลด stock — ไม่ติดลบ (GREATEST 0) + update isAvailable + ไม่แตะ FRESH_STOCK
     * 
     * @return จำนวน row ที่ update (0 หรือ 1)
     */
    @Transactional
    public int decreaseStock(Long productId, int quantity) {
        String sql = "UPDATE tb_products " +
                "SET \"stockQuantity\" = GREATEST(\"stockQuantity\" - ?, 0), " +
                "    \"isAvailable\" = (GREATEST(\"stockQuantity\" - ?, 0) > 0) " +
                "WHERE id = ? AND \"stockQuantity\" != ?";
        return jdbcTemplate.update(sql, quantity, quantity, productId, FRESH_STOCK_VALUE);
    }

    /**
     * คืน stock + เปิด isAvailable = true
     * 
     * @return จำนวน row ที่ update
     */
    @Transactional
    public int increaseStock(Long productId, int quantity) {
        String sql = "UPDATE tb_products " +
                "SET \"stockQuantity\" = \"stockQuantity\" + ?, \"isAvailable\" = true " +
                "WHERE id = ? AND \"stockQuantity\" != ?";
        return jdbcTemplate.update(sql, quantity, productId, FRESH_STOCK_VALUE);
    }

    /**
     * คืน stock แบบ batch — สำหรับ cancel order ที่มีหลาย items
     * แก้ N+1: ทำใน 1 round-trip
     */
    @Transactional
    public int[] batchIncreaseStock(List<StockChange> items) {
        if (items == null || items.isEmpty())
            return new int[0];

        String sql = "UPDATE tb_products " +
                "SET \"stockQuantity\" = \"stockQuantity\" + ?, \"isAvailable\" = true " +
                "WHERE id = ? AND \"stockQuantity\" != " + FRESH_STOCK_VALUE;

        return jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                StockChange item = items.get(i);
                ps.setInt(1, item.quantity());
                ps.setLong(2, item.productId());
            }

            @Override
            public int getBatchSize() {
                return items.size();
            }
        });
    }

    /**
     * ดึง image ของ product (ใช้ใน getOrderById เมื่อ order_item.image == null)
     * 
     * @return image URL หรือ null ถ้าไม่พบ
     */
    public String findImage(Long productId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT image FROM tb_products WHERE id = ?",
                    String.class,
                    productId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.warn("Failed to fetch image for productId={}", productId, e);
            return null;
        }
    }

    /** DTO สำหรับ batch update */
    public record StockChange(Long productId, int quantity) {
    }
}