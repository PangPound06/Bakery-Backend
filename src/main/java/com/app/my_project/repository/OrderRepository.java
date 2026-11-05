package com.app.my_project.repository;

import com.app.my_project.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long>, JpaSpecificationExecutor<OrderEntity> {

    // ✅ ใช้ email แทน userId
    List<OrderEntity> findByEmailOrderByCreatedAtDesc(String email);

    List<OrderEntity> findAllByOrderByCreatedAtDesc();

    // ✅ Paginated versions — สำหรับ list endpoints ที่อาจมีข้อมูลจำนวนมาก
    Page<OrderEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<OrderEntity> findByEmailOrderByCreatedAtDesc(String email, Pageable pageable);

    Page<OrderEntity> findByEmailAndOrderTypeOrderByCreatedAtDesc(String email, String orderType, Pageable pageable);

    List<OrderEntity> findByOrderStatus(String orderStatus);

    List<OrderEntity> findByPaymentStatus(String paymentStatus);

    Optional<OrderEntity> findByOrdCode(String ordCode);

    /** หา order ที่ยังไม่จ่ายของ user (awaiting_payment / expired / failed) */
    List<OrderEntity> findByEmailAndPaymentStatus(String email, String paymentStatus);

    /** order ค้างจ่ายของวิธีชำระเงินหนึ่งที่สร้างก่อนเวลาที่กำหนด — ใช้ปิด order ที่ถูกทิ้งไว้ */
    List<OrderEntity> findByPaymentMethodAndPaymentStatusAndCreatedAtBefore(
            String paymentMethod, String paymentStatus, LocalDateTime createdBefore);

    void deleteByEmail(String email);
}