package com.app.my_project.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class NoCacheFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletResponse httpResponse = (HttpServletResponse) response;
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        String path = httpRequest.getRequestURI();

        // ใช้กับ API และ route ที่ต้องล็อกอินทั้งหมด
        if (path.startsWith("/api/") || path.startsWith("/admin/") || path.startsWith("/user/")) {
            httpResponse.setHeader("Cache-Control", "no-cache, no-store, must-revalidate, private");
            httpResponse.setHeader("Pragma", "no-cache");
            httpResponse.setDateHeader("Expires", 0);
        }

        chain.doFilter(request, response);
    }
}
