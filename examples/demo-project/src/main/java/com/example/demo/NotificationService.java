package com.example.demo;

/** Outbound notifications (email, SMS, ...). */
public interface NotificationService {

    void notifyCustomer(String customerEmail, String message);
}
