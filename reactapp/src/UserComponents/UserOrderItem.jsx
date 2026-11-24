import React, { useState } from "react";
import { cancelMyOrder } from "../utils/api.js";

const formatCurrency = (amount) =>
  new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  }).format(amount || 0);

const formatDate = (value) => {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
};

// Dark-dashboard compatible status badges (dashboard background is #12121b).
const STATUS_STYLES = {
  PENDING: "bg-yellow-500/15 text-yellow-400 ring-yellow-500/40",
  PROCESSING: "bg-blue-500/15 text-blue-400 ring-blue-500/40",
  SHIPPED: "bg-indigo-500/15 text-indigo-400 ring-indigo-500/40",
  DELIVERED: "bg-green-500/15 text-green-400 ring-green-500/40",
  CANCELLED: "bg-red-500/15 text-red-400 ring-red-500/40",
};

// Payment badges mirror the stored Payment.status values (never client-derived).
const PAYMENT_STYLES = {
  SUCCESS: "bg-green-500/15 text-green-400 ring-green-500/40",
  PENDING: "bg-yellow-500/15 text-yellow-400 ring-yellow-500/40",
  FAILED: "bg-red-500/15 text-red-400 ring-red-500/40",
};

/**
 * Renders ONE customer order from the actual /api/orders/my contract:
 * { id, orderDate, status, totalAmount, couponCode, discountAmount,
 *   orderItems: [{ id, quantity, priceAtPurchase, product: { name, ... } }] }
 * The old version read order.productName / order.quantity, which do not exist.
 */
const UserOrderItem = ({ order, onCancel }) => {
  const [expanded, setExpanded] = useState(false);

  const items = Array.isArray(order.orderItems) ? order.orderItems : [];
  const status = (order.status || "").toUpperCase();
  // Phase 13D: payment comes from the stored Payment row projected by the backend;
  // null means the order has no payment record yet.
  const payment = order.payment || null;
  const paymentStatus = payment && payment.status ? String(payment.status).toUpperCase() : "";
  // Backend only permits cancelling PENDING/PROCESSING orders.
  const canCancel = status === "PENDING" || status === "PROCESSING";
  const totalQuantity = items.reduce((sum, item) => sum + (item.quantity || 0), 0);
  const productNames = items.map((item) => item.product?.name).filter(Boolean).join(", ");

  const handleCancel = () => {
    if (!window.confirm(`Cancel order #${order.id}?`)) return;
    // Use the customer-scoped cancel endpoint (NOT the admin status endpoint).
    // On success, parent only refreshes local state — no second API call.
    cancelMyOrder(order.id)
      .then(() => onCancel(order.id))
      .catch((err) => {
        const msg = err.response?.data?.message || "Failed to cancel order.";
        alert(msg);
      });
  };

  return (
    <li className="border border-[#2d2d44] bg-[#1f1f2e] rounded-lg p-4">
      <div className="flex flex-col sm:flex-row sm:justify-between sm:items-start gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold text-white">Order #{order.id}</span>
            <span
              className={`text-xs px-2 py-0.5 rounded-full ring-1 ${
                STATUS_STYLES[status] || "bg-gray-500/15 text-gray-300 ring-gray-500/40"
              }`}
            >
              {status || "UNKNOWN"}
            </span>
            <span
              className={`text-xs px-2 py-0.5 rounded-full ring-1 ${
                payment
                  ? PAYMENT_STYLES[paymentStatus] || "bg-gray-500/15 text-gray-300 ring-gray-500/40"
                  : "bg-gray-500/15 text-gray-400 ring-gray-500/40"
              }`}
            >
              {payment ? `Payment: ${paymentStatus || "UNKNOWN"}` : "No payment recorded"}
            </span>
          </div>
          <p className="text-sm text-gray-400 mt-1">Placed: {formatDate(order.orderDate)}</p>
          <p className="text-sm text-gray-300 mt-1 truncate">
            {productNames || "No item details"}
            {totalQuantity > 0 && ` — ${totalQuantity} item${totalQuantity > 1 ? "s" : ""}`}
          </p>
          <div className="mt-2 flex flex-wrap items-center gap-3 text-sm">
            <span className="text-white font-medium">
              Total: {formatCurrency(order.totalAmount)}
            </span>
            {order.discountAmount > 0 && (
              <span className="text-green-400">
                Discount{order.couponCode ? ` (${order.couponCode})` : ""}: -
                {formatCurrency(order.discountAmount)}
              </span>
            )}
          </div>
        </div>
        <div className="flex sm:flex-col items-start sm:items-end gap-2 shrink-0">
          <button
            onClick={() => setExpanded((v) => !v)}
            className="text-xs px-3 py-1 rounded border border-[#3b3b5c] text-gray-300 hover:text-white hover:border-blue-500 transition-colors cursor-pointer"
          >
            {expanded ? "Hide details" : "View details"}
          </button>
          {canCancel && (
            <button
              onClick={handleCancel}
              className="bg-red-600 hover:bg-red-700 text-white text-xs px-3 py-1 rounded transition-colors cursor-pointer"
            >
              Cancel
            </button>
          )}
        </div>
      </div>

      {expanded && (
        <div className="mt-3 border-t border-[#2d2d44] pt-3 overflow-x-auto">
          <table className="w-full text-sm text-left">
            <thead>
              <tr className="text-gray-400 text-xs uppercase">
                <th className="py-1 pr-3">Product</th>
                <th className="py-1 pr-3 text-right">Qty</th>
                <th className="py-1 pr-3 text-right">Unit price</th>
                <th className="py-1 text-right">Subtotal</th>
              </tr>
            </thead>
            <tbody className="text-gray-200">
              {items.map((item) => (
                <tr key={item.id} className="border-t border-[#2d2d44]">
                  <td className="py-1.5 pr-3">{item.product?.name || "Product"}</td>
                  <td className="py-1.5 pr-3 text-right">{item.quantity}</td>
                  <td className="py-1.5 pr-3 text-right">
                    {formatCurrency(item.priceAtPurchase)}
                  </td>
                  <td className="py-1.5 text-right">
                    {formatCurrency((item.priceAtPurchase || 0) * (item.quantity || 0))}
                  </td>
                </tr>
              ))}
              {items.length === 0 && (
                <tr>
                  <td colSpan={4} className="py-2 text-gray-400">
                    No item details available for this order.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <div className="mt-2 flex justify-end items-center gap-4 text-sm">
            {order.discountAmount > 0 && (
              <span className="text-green-400">
                Discount{order.couponCode ? ` (${order.couponCode})` : ""}: -
                {formatCurrency(order.discountAmount)}
              </span>
            )}
            <span className="text-white font-semibold">
              Order total: {formatCurrency(order.totalAmount)}
            </span>
          </div>

          {/* Payment: only stored fields are displayed. */}
          <div className="mt-3 border-t border-[#2d2d44] pt-3">
            <h4 className="text-xs font-semibold uppercase tracking-wide text-gray-400 mb-2">
              Payment
            </h4>
            {payment ? (
              <dl className="grid grid-cols-2 sm:grid-cols-4 gap-x-4 gap-y-2 text-sm">
                <div>
                  <dt className="text-gray-400 text-xs">Status</dt>
                  <dd className="text-gray-100">{paymentStatus || "UNKNOWN"}</dd>
                </div>
                <div>
                  <dt className="text-gray-400 text-xs">Method</dt>
                  <dd className="text-gray-100">{payment.method || "—"}</dd>
                </div>
                <div>
                  <dt className="text-gray-400 text-xs">Amount</dt>
                  <dd className="text-gray-100">{formatCurrency(payment.amount)}</dd>
                </div>
                <div>
                  <dt className="text-gray-400 text-xs">Recorded</dt>
                  <dd className="text-gray-100">{formatDate(payment.createdAt)}</dd>
                </div>
                {payment.transactionRef && (
                  <div className="col-span-2">
                    <dt className="text-gray-400 text-xs">Transaction ref</dt>
                    <dd className="text-gray-100 font-mono text-xs break-all">
                      {payment.transactionRef}
                    </dd>
                  </div>
                )}
                {payment.invoiceNumber && (
                  <div className="col-span-2">
                    <dt className="text-gray-400 text-xs">Invoice</dt>
                    <dd className="text-gray-100 font-mono text-xs">{payment.invoiceNumber}</dd>
                  </div>
                )}
              </dl>
            ) : (
              <p className="text-sm text-gray-400">
                No payment has been recorded for this order yet.
              </p>
            )}
          </div>
        </div>
      )}
    </li>
  );
};

export default UserOrderItem;
