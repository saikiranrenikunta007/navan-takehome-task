package com.navan.task.transaction;

import com.navan.task.common.ItemizeStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_id", nullable = false, unique = true)
    private Long receiptId;

    @Column(name = "merchant", nullable = false)
    private String merchant;

    @Column(name = "txn_date", nullable = false)
    private LocalDate date;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "grand_total", nullable = false)
    private BigDecimal grandTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "itemize_status", nullable = false, length = 32)
    private ItemizeStatus itemizeStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<Tax> taxes = new ArrayList<>();

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<LineItem> lineItems = new ArrayList<>();

    public void replaceTaxes(List<Tax> newTaxes) {
        this.taxes.clear();
        for (Tax t : newTaxes) {
            t.setTransaction(this);
            this.taxes.add(t);
        }
    }

    public void replaceLineItems(List<LineItem> newItems) {
        this.lineItems.clear();
        for (LineItem i : newItems) {
            i.setTransaction(this);
            this.lineItems.add(i);
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReceiptId() {
        return receiptId;
    }

    public void setReceiptId(Long receiptId) {
        this.receiptId = receiptId;
    }

    public String getMerchant() {
        return merchant;
    }

    public void setMerchant(String merchant) {
        this.merchant = merchant;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    public void setGrandTotal(BigDecimal grandTotal) {
        this.grandTotal = grandTotal;
    }

    public ItemizeStatus getItemizeStatus() {
        return itemizeStatus;
    }

    public void setItemizeStatus(ItemizeStatus itemizeStatus) {
        this.itemizeStatus = itemizeStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<Tax> getTaxes() {
        return taxes;
    }

    public List<LineItem> getLineItems() {
        return lineItems;
    }
}
