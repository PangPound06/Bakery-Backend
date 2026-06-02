package com.app.my_project.service;

import com.app.my_project.models.ProductModel;
import com.app.my_project.models.ProductRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test ProductService — mock JDBC chain (DataSource → Connection → PreparedStatement → ResultSet)
 * ครอบ: map ResultSet → ProductModel, create (generated key), update/delete (affected rows),
 * exists, และการ wrap SQLException เป็น RuntimeException
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection conn;
    @Mock
    private PreparedStatement stmt;
    @Mock
    private ResultSet rs;

    @InjectMocks
    private ProductService service;

    /** stub คอลัมน์ครบ 1 แถวสำหรับ BASE_SELECT */
    private void stubProductRow() throws SQLException {
        when(rs.getLong("id")).thenReturn(7L);
        when(rs.getString("name")).thenReturn("ครัวซองต์");
        when(rs.getDouble("price")).thenReturn(55.0);
        when(rs.getString("cat_slug")).thenReturn("bakery");
        when(rs.getLong("category_id")).thenReturn(2L);
        when(rs.getString("cat_name")).thenReturn("เบเกอรี่");
        when(rs.getString("cat_icon")).thenReturn("🥐");
        when(rs.getString("image")).thenReturn("img.png");
        when(rs.getString("type")).thenReturn("food");
        when(rs.getString("description")).thenReturn("อร่อย");
        when(rs.getLong("stockQuantity")).thenReturn(10L);
        when(rs.getBoolean("isAvailable")).thenReturn(true);
        when(rs.getString("options")).thenReturn(null);
    }

    private ProductRequest request() {
        ProductRequest r = new ProductRequest();
        r.setName("ครัวซองต์");
        r.setPrice(55.0);
        r.setCategoryId(2L); // มี categoryId → ไม่ต้อง lookup จาก slug
        r.setStockQuantity(10L);
        r.setIsAvailable(true);
        return r;
    }

    @Nested
    @DisplayName("getAll / getById / getByCategory")
    class Reads {
        @Test
        @DisplayName("getAll: map ResultSet → ProductModel ครบ field")
        void getAllMapsRows() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true, false); // 1 แถว
            stubProductRow();

            List<ProductModel> all = service.getAll();

            assertThat(all).hasSize(1);
            ProductModel p = all.get(0);
            assertThat(p.getId()).isEqualTo(7L);
            assertThat(p.getName()).isEqualTo("ครัวซองต์");
            assertThat(p.getPrice()).isEqualTo(55.0);
            assertThat(p.getCategory()).isEqualTo("bakery");
            assertThat(p.getCategoryId()).isEqualTo(2L);
            assertThat(p.getIsAvailable()).isTrue();
        }

        @Test
        @DisplayName("getById: พบ → Optional ที่มีค่า + set พารามิเตอร์ id")
        void getByIdFound() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true);
            stubProductRow();

            Optional<ProductModel> result = service.getById(7L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(7L);
            verify(stmt).setLong(1, 7L);
        }

        @Test
        @DisplayName("getById: ไม่พบ → Optional.empty")
        void getByIdNotFound() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(false);

            assertThat(service.getById(99L)).isEmpty();
        }

        @Test
        @DisplayName("getByCategory: คืนรายการสินค้าในหมวด")
        void getByCategory() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true, false);
            stubProductRow();

            List<ProductModel> list = service.getByCategory("bakery");

            assertThat(list).hasSize(1);
            verify(stmt).setString(1, "bakery");
        }
    }

    @Nested
    @DisplayName("exists")
    class Exists {
        @Test
        @DisplayName("มีแถว → true")
        void existsTrue() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true);
            assertThat(service.exists(7L)).isTrue();
        }

        @Test
        @DisplayName("ไม่มีแถว → false")
        void existsFalse() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(false);
            assertThat(service.exists(99L)).isFalse();
        }
    }

    @Nested
    @DisplayName("create")
    class Create {
        @Test
        @DisplayName("insert สำเร็จ → คืน generated id")
        void createReturnsGeneratedId() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
                    .thenReturn(stmt);
            when(stmt.executeUpdate()).thenReturn(1);
            when(stmt.getGeneratedKeys()).thenReturn(rs);
            when(rs.next()).thenReturn(true);
            when(rs.getLong(1)).thenReturn(42L);

            assertThat(service.create(request())).isEqualTo(42L);
        }

        @Test
        @DisplayName("ไม่มีแถวถูก insert → คืน null")
        void createReturnsNullWhenNoRows() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
                    .thenReturn(stmt);
            when(stmt.executeUpdate()).thenReturn(0);

            assertThat(service.create(request())).isNull();
        }
    }

    @Nested
    @DisplayName("update / delete")
    class UpdateDelete {
        @Test
        @DisplayName("update: มีแถวถูกแก้ → true + set id ที่พารามิเตอร์ 11")
        void updateTrue() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeUpdate()).thenReturn(1);

            assertThat(service.update(5L, request())).isTrue();
            verify(stmt).setLong(11, 5L);
        }

        @Test
        @DisplayName("update: ไม่มีแถว → false")
        void updateFalse() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeUpdate()).thenReturn(0);
            assertThat(service.update(5L, request())).isFalse();
        }

        @Test
        @DisplayName("delete: มีแถวถูกลบ → true")
        void deleteTrue() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeUpdate()).thenReturn(1);
            assertThat(service.delete(5L)).isTrue();
            verify(stmt).setLong(1, 5L);
        }

        @Test
        @DisplayName("delete: ไม่มีแถว → false")
        void deleteFalse() throws SQLException {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeUpdate()).thenReturn(0);
            assertThat(service.delete(5L)).isFalse();
        }
    }

    @Test
    @DisplayName("SQLException ถูก wrap เป็น RuntimeException")
    void wrapsSqlException() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThatThrownBy(() -> service.getAll())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }
}