package com.billtracker.parsing;

import com.billtracker.bill.ChargeCategory;

import java.math.BigDecimal;

public record ParsedCharge(String description, ChargeCategory category, BigDecimal amount) {
}
