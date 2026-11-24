package com.examly.springapp.e2e;

import com.examly.springapp.model.Order;
import com.examly.springapp.model.OrderItem;
import com.examly.springapp.model.Product;
import com.examly.springapp.model.Role;
import com.examly.springapp.model.User;
import com.examly.springapp.repository.OrderRepository;
import com.examly.springapp.repository.ProductRepository;
import com.examly.springapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * Playwright fixtures for the isolated E2E run ({@code e2e} profile only).
 *
 * <p>Runs against the in-memory H2 database configured in
 * {@code src/test/resources/application-e2e.properties}; it never touches the
 * application's configured MySQL database. The class lives in test sources and
 * is inactive unless the e2e profile is explicitly enabled.
 */
@Component
@Profile("e2e")
@RequiredArgsConstructor
public class E2ESeedData implements CommandLineRunner {

    public static final String ADMIN_EMAIL = "admin@e2e.test";
    public static final String ADMIN_PASSWORD = "Admin@12345";
    public static final String CUSTOMER_EMAIL = "customer@e2e.test";
    public static final String CUSTOMER_PASSWORD = "Customer@12345";

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.findByEmail(ADMIN_EMAIL).isPresent()) {
            return;
        }

        User admin = userRepository.save(user(ADMIN_EMAIL, "E2E Admin", ADMIN_PASSWORD, Role.ADMIN));
        User customer = userRepository.save(user(CUSTOMER_EMAIL, "E2E Customer", CUSTOMER_PASSWORD, Role.USER));

        // 14 products -> two catalogue pages at the 12-item page size.
        for (int i = 1; i <= 14; i++) {
            productRepository.save(Product.builder()
                    .name(String.format("E2E Product %02d", i))
                    .description("Seeded E2E product " + i)
                    .price(10.0 * i)
                    .category("E2E Category")
                    .stockQuantity(100)
                    .build());
        }

        // One historical, UNPAID order so the customer history shows the
        // "No payment recorded" state before the checkout test creates a paid one.
        Product firstProduct = productRepository.findAll().stream()
                .filter(p -> "E2E Product 01".equals(p.getName()))
                .findFirst()
                .orElseThrow();
        Order order = Order.builder()
                .customerName("E2E Customer")
                .customerEmail(CUSTOMER_EMAIL)
                .shippingAddress("1 E2E Street")
                .orderDate(LocalDateTime.now().minusDays(1))
                .status("DELIVERED")
                .userId(customer.getId())
                .orderItems(new ArrayList<>())
                .totalAmount(firstProduct.getPrice())
                .discountAmount(0.0)
                .build();
        OrderItem item = OrderItem.builder()
                .product(firstProduct)
                .order(order)
                .quantity(1)
                .priceAtPurchase(firstProduct.getPrice())
                .build();
        order.getOrderItems().add(item);
        orderRepository.save(order);
    }

    private User user(String email, String name, String rawPassword, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setName(name);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setRole(role);
        u.setActive(true);
        u.setCreatedAt(LocalDateTime.now());
        return u;
    }
}
