package com.app.my_project.service;

import com.app.my_project.common.ProductQuantityHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * PricingService — แหล่งความจริงเดียวเรื่อง "ราคา" ของระบบ
 *
 * ═══ ทำไมต้องมีคลาสนี้ ═══
 * เดิมราคาถูกคำนวณที่ฝั่ง browser (ProductDetail.tsx + checkout/page.tsx)
 * แล้วส่งตัวเลขมาให้ backend บันทึกตามนั้น → ยิง API ตรงๆ สั่งของ 5,000 บาท
 * โดยจ่าย 1 บาทได้ พอต่อ Stripe เข้าไป = เก็บเงินผิดจริง
 *
 * คลาสนี้คำนวณราคาใหม่ทั้งหมดจาก tb_products เท่านั้น ไม่แตะตัวเลขจาก client
 *
 * ═══ กติกาที่ port มาจาก frontend ═══
 * ProductDetail.tsx บรรทัด 46-50 และ 217-219:
 *   basePrice  = product.price + (option.extraPrice ?? 0)
 *   finalPrice = isCake ? basePrice * multiplier : basePrice
 *   multiplier: แบบชิ้น=1, 1 ปอนด์=8, 2 ปอนด์=16
 *
 * ค่าจัดส่ง (checkout/page.tsx บรรทัด 66-70 และ cart/page.tsx บรรทัด 50):
 *   subtotal < 100  → 50
 *   subtotal <= 500 → 20
 *   มากกว่านั้น     → 0
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    /** stock = 9999 หมายถึงสินค้าทำสด ไม่นับ stock */
    private static final long FRESH_STOCK_VALUE = 9999L;

    private final JdbcTemplate jdbcTemplate;
    private final ProductQuantityHelper qtyHelper;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public PricingService(JdbcTemplate jdbcTemplate, ProductQuantityHelper qtyHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.qtyHelper = qtyHelper;
    }

    // ═════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════

    /**
     * ราคาต่อ 1 ออเดอร์ของสินค้าชิ้นนั้น (รวม option + ขนาดเค้กแล้ว)
     * ใช้ตอน add to cart — จะได้ไม่ต้องเชื่อ price ที่ client ส่งมา
     *
     * @throws ProductNotFoundException ถ้าไม่มี product id นี้
     */
    public double resolveUnitPrice(Long productId, String selectedOption) {
        ProductRow p = loadProduct(productId);
        return computeUnitPrice(p, selectedOption);
    }

    /**
     * คำนวณราคาตะกร้าของ user ใหม่ทั้งใบจาก tb_products
     * ตรวจ stock / isAvailable ไปด้วยในตัว
     *
     * @throws CartValidationException ถ้าตะกร้าว่าง สินค้าหมด หรือปิดขาย
     */
    public PricedCart priceCart(String email) {
        String sql = "SELECT c.product_id, c.quantity, c.selected_option, " +
                "       p.name, p.price, p.options, p.category, p.image, " +
                "       p.\"stockQuantity\" AS stock_qty, p.\"isAvailable\" AS is_available " +
                "FROM tb_cart c " +
                "JOIN tb_products p ON p.id = c.product_id " +
                "WHERE c.email = ? " +
                "ORDER BY c.id";

        List<PricedLine> lines = new ArrayList<>();

        List<Object[]> rows = jdbcTemplate.query(sql, (rs, i) -> new Object[] {
                rs.getLong("product_id"),
                rs.getInt("quantity"),
                rs.getString("selected_option"),
                rs.getString("name"),
                rs.getDouble("price"),
                rs.getString("options"),
                rs.getString("category"),
                rs.getString("image"),
                rs.getLong("stock_qty"),
                rs.getBoolean("is_available")
        }, email);

        if (rows.isEmpty()) {
            throw new CartValidationException("ตะกร้าสินค้าว่าง");
        }

        for (Object[] r : rows) {
            Long productId = (Long) r[0];
            int rawQty = (Integer) r[1]; // เก็บเป็น "ชิ้น" ในตาราง tb_cart
            String option = (String) r[2];
            String name = (String) r[3];
            double basePrice = (Double) r[4];
            String optionsJson = (String) r[5];
            String category = (String) r[6];
            String image = (String) r[7];
            long stock = (Long) r[8];
            boolean available = (Boolean) r[9];

            if (!available) {
                throw new CartValidationException("สินค้า \"" + name + "\" ปิดการขายแล้ว กรุณานำออกจากตะกร้า");
            }
            if (stock != FRESH_STOCK_VALUE && stock < rawQty) {
                throw new CartValidationException(
                        "สินค้า \"" + name + "\" คงเหลือไม่พอ (เหลือ " + stock + " ชิ้น)");
            }

            ProductRow p = new ProductRow(productId, name, basePrice, optionsJson, category, image);
            double unitPrice = computeUnitPrice(p, option);
            int displayQty = qtyHelper.toDisplayQty(rawQty, option);
            if (displayQty < 1) {
                // กันกรณีข้อมูลตะกร้าเพี้ยน เช่น quantity=3 แต่ option เป็น 1 ปอนด์ (ต้องหาร 8 ลงตัว)
                throw new CartValidationException("ข้อมูลจำนวนสินค้า \"" + name + "\" ไม่ถูกต้อง กรุณาลบแล้วเพิ่มใหม่");
            }

            lines.add(new PricedLine(
                    productId, name, image, category, option,
                    round2(unitPrice), rawQty, displayQty,
                    round2(unitPrice * displayQty)));
        }

        double subtotal = round2(lines.stream().mapToDouble(PricedLine::lineTotal).sum());
        double shipping = shippingFor(subtotal);
        return new PricedCart(lines, subtotal, shipping, round2(subtotal + shipping));
    }

    /** ค่าจัดส่งตามยอดสินค้า — กติกาเดียวกับที่แสดงในหน้า cart */
    public double shippingFor(double subtotal) {
        if (subtotal < 100)
            return 50.0; // แก้จาก 50 → 10 ตามที่ตกลงกันในทีม เดี๋ยวกลับมาเปลี่ยนเป็น 50 ถ้าอยากให้เหมือนเดิม
        if (subtotal <= 500)
            return 20.0;
        return 0.0;
    }

    // ═════════════════════════════════════════════════════════════════
    // INTERNAL
    // ═════════════════════════════════════════════════════════════════

    private ProductRow loadProduct(Long productId) {
        if (productId == null)
            throw new ProductNotFoundException("ไม่ได้ระบุรหัสสินค้า");
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, name, price, options, category, image FROM tb_products WHERE id = ?",
                    (rs, i) -> new ProductRow(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getDouble("price"),
                            rs.getString("options"),
                            rs.getString("category"),
                            rs.getString("image")),
                    productId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ProductNotFoundException("ไม่พบสินค้ารหัส " + productId);
        }
    }

    /** (ราคาสินค้า + extraPrice ของ option) × ตัวคูณขนาดเค้ก */
    private double computeUnitPrice(ProductRow p, String selectedOption) {
        double extra = extraPriceOf(p.optionsJson(), extractOptionName(selectedOption));
        int multiplier = qtyHelper.getMultiplier(selectedOption);
        return (p.price() + extra) * multiplier;
    }

    /**
     * แกะชื่อ option ออกจาก label ที่ frontend ประกอบไว้
     *
     * รูปแบบที่เป็นไปได้ (ProductDetail.tsx บรรทัด 220-224):
     * เค้ก: "1 ปอนด์ (8 ชิ้น) - ช็อกโกแลต" หรือ "แบบชิ้น"
     * ทั่วไป: "ช็อกโกแลต" หรือ null
     */
    String extractOptionName(String label) {
        if (label == null || label.isBlank())
            return null;
        int sep = label.indexOf(" - ");
        if (sep >= 0)
            return label.substring(sep + 3).trim();
        // ไม่มีตัวคั่น → ถ้าเป็น label ขนาดเค้กล้วนๆ แปลว่าไม่ได้เลือก option
        if (label.contains("ปอนด์") || label.startsWith("แบบชิ้น"))
            return null;
        return label.trim();
    }

    /** หา extraPrice จาก JSON column options ของ product */
    double extraPriceOf(String optionsJson, String optionName) {
        if (optionsJson == null || optionsJson.isBlank() || optionName == null)
            return 0.0;
        try {
            JsonNode arr = jsonMapper.readTree(optionsJson);
            if (!arr.isArray())
                return 0.0;
            for (JsonNode node : arr) {
                String name = node.path("name").asString("");
                if (optionName.equals(name.trim())) {
                    return node.path("extraPrice").asDouble(0.0);
                }
            }
        } catch (Exception e) {
            log.warn("อ่าน options JSON ไม่ได้: {}", e.getMessage());
        }
        return 0.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ═════════════════════════════════════════════════════════════════
    // DTOs & Exceptions
    // ═════════════════════════════════════════════════════════════════

    private record ProductRow(Long id, String name, double price, String optionsJson,
            String category, String image) {
    }

    /**
     * 1 บรรทัดในตะกร้าที่คำนวณราคาใหม่แล้ว
     *
     * @param unitPrice      ราคาต่อ 1 ออเดอร์ (เช่น เค้ก 1 ปอนด์ = ราคาเต็มปอนด์)
     * @param rawQuantity    จำนวน "ชิ้น" — ค่าที่ใช้ตัด stock และเก็บใน
     *                       tb_order_items
     * @param displayQuantity จำนวน "ออเดอร์" — ค่าที่คูณกับ unitPrice และโชว์ลูกค้า
     */
    public record PricedLine(
            Long productId,
            String productName,
            String image,
            String category,
            String selectedOption,
            double unitPrice,
            int rawQuantity,
            int displayQuantity,
            double lineTotal) {
    }

    public record PricedCart(
            List<PricedLine> lines,
            double subtotal,
            double shipping,
            double total) {
    }

    /** ตะกร้ามีปัญหา — ควรตอบ 400 ให้ลูกค้าแก้ */
    public static class CartValidationException extends RuntimeException {
        public CartValidationException(String message) {
            super(message);
        }
    }

    public static class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(String message) {
            super(message);
        }
    }
}
