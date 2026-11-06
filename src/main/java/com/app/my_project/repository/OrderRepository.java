package com.app.my_project.repository;

import com.app.my_project.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.Collection;
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

    /** order ของ user ตามสถานะการจ่ายเงิน */
    List<OrderEntity> findByEmailAndPaymentStatus(String email, String paymentStatus);

    /** order ที่ค้างสถานะนี้ของวิธีชำระเงินเหล่านี้ และสร้างก่อนเวลาที่กำหนด — ใช้เก็บกวาด order ที่ถูกทิ้งไว้ */
    List<OrderEntity> findByPaymentStatusAndPaymentMethodInAndCreatedAtBefore(
            String paymentStatus, Collection<String> paymentMethods, LocalDateTime createdBefore);

    /** สลิป (payment_id) นี้ถูกใช้กับ order อื่นแล้วหรือยัง */
    boolean existsByPaymentIdAndIdNot(String paymentId, Long id);

    void deleteByEmail(String email);
}