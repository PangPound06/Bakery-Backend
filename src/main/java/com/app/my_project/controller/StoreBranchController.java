package com.app.my_project.controller;

import com.app.my_project.common.ApiResponse;
import com.app.my_project.entity.StoreBranchEntity;
import com.app.my_project.service.JwtService;
import com.app.my_project.service.StoreBranchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * สาขาร้าน
 *  - GET    /api/store/branches       (public) สาขาที่เปิดใช้งาน → หน้า location ลูกค้า
 *  - GET    /api/store/branches/all   (admin)  ทุกสาขา (รวมปิด)
 *  - POST   /api/store/branches       (admin)  เพิ่มสาขา
 *  - PUT    /api/store/branches/{id}  (admin)  แก้สาขา
 *  - DELETE /api/store/branches/{id}  (admin)  ลบสาขา
 */
@RestController
@RequestMapping("/api/store/branches")
public class StoreBranchController {

    private final StoreBranchService service;
    private final JwtService jwtService;

    public StoreBranchController(StoreBranchService service, JwtService jwtService) {
        this.service = service;
        this.jwtService = jwtService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listActive() {
        return ApiResponse.ok(Map.of("branches", service.listActive()));
    }

    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> listAll(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!jwtService.isAdmin(auth)) return ApiResponse.forbidden();
        return ApiResponse.ok(Map.of("branches", service.listAll()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!jwtService.isAdmin(auth)) return ApiResponse.forbidden();

        StoreBranchEntity b = new StoreBranchEntity();
        apply(b, body);
        String err = validate(b);
        if (err != null) return ApiResponse.badRequest(err);

        return ApiResponse.ok("เพิ่มสาขาแล้ว", Map.of("branch", service.save(b)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!jwtService.isAdmin(auth)) return ApiResponse.forbidden();

        StoreBranchEntity b = service.findById(id).orElse(null);
        if (b == null) return ApiResponse.notFound("ไม่พบสาขานี้");

        apply(b, body);
        String err = validate(b);
        if (err != null) return ApiResponse.badRequest(err);

        return ApiResponse.ok("อัปเดตสาขาแล้ว", Map.of("branch", service.save(b)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!jwtService.isAdmin(auth)) return ApiResponse.forbidden();
        if (!service.delete(id)) return ApiResponse.notFound("ไม่พบสาขานี้");
        return ApiResponse.ok("ลบสาขาแล้ว");
    }

    // ---------- helpers ----------

    private String validate(StoreBranchEntity b) {
        if (b.getName() == null || b.getName().isBlank()) return "กรุณากรอกชื่อสาขา";
        if (b.getLatitude() == null || b.getLongitude() == null) return "กรุณากรอกพิกัด (latitude/longitude)";
        return null;
    }

    private void apply(StoreBranchEntity b, Map<String, Object> body) {
        if (body.containsKey("name")) b.setName(str(body.get("name")));
        if (body.containsKey("latitude")) b.setLatitude(dbl(body.get("latitude")));
        if (body.containsKey("longitude")) b.setLongitude(dbl(body.get("longitude")));
        if (body.containsKey("address")) b.setAddress(str(body.get("address")));
        if (body.containsKey("phone")) b.setPhone(str(body.get("phone")));
        if (body.containsKey("hours")) b.setHours(str(body.get("hours")));
        if (body.containsKey("active")) b.setActive(Boolean.TRUE.equals(body.get("active")));
        if (body.containsKey("sortOrder")) b.setSortOrder(intval(body.get("sortOrder")));
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString().trim()); } catch (Exception e) { return null; }
    }

    private static int intval(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return 0; }
    }
}