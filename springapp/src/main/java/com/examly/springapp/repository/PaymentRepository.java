package com.examly.springapp.repository;

import com.examly.springapp.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByOrderId(Long orderId);

    /** Batch lookup for a page of orders (avoids one query per order). */
    List<Payment> findByOrderIdIn(Collection<Long> orderIds);
}
