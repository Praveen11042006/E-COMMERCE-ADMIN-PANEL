import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  fetchProductsPaged,
  createOrder,
  validateCoupon,
  payOrder,
  fetchInvoice,
} from '../utils/api';

const PAGE_SIZE = 12;
const SORT_MAP = {
  'name-asc': 'name,asc',
  'name-desc': 'name,desc',
  'price-asc': 'price,asc',
  'price-desc': 'price,desc',
  'stock-asc': 'stockQuantity,asc',
  'stock-desc': 'stockQuantity,desc',
};

const PlaceOrder = () => {
  const [order, setOrder] = useState({
    customerName: '',
    customerEmail: '',
    shippingAddress: '',
    orderItems: []
  });
  // One page of the catalogue at a time; `catalog` remembers every product seen so a
  // cart line added on another page still resolves its price, stock and name.
  const [pageProducts, setPageProducts] = useState([]);
  const [catalog, setCatalog] = useState({});
  const [meta, setMeta] = useState({ page: 0, totalPages: 0, totalElements: 0, first: true, last: true });
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [alert, setAlert] = useState({ show: false, message: '', type: '' });
  const [searchTerm, setSearchTerm] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('');
  const [sortBy, setSortBy] = useState('name-asc');
  const [couponCode, setCouponCode] = useState('');
  const [coupon, setCoupon] = useState(null);
  const [couponMessage, setCouponMessage] = useState('');
  // Phase 9 checkout completion: order → payment → invoice hand-off states.
  // `createdOrder` holds the raw backend response of POST /api/orders — the backend
  // is authoritative for the order id and total amount.
  const [createdOrder, setCreatedOrder] = useState(null);
  const [payment, setPayment] = useState(null);
  const [paying, setPaying] = useState(false);
  const [invoice, setInvoice] = useState(null);
  const [invoiceLoading, setInvoiceLoading] = useState(false);
  const [panelError, setPanelError] = useState('');
  const navigate = useNavigate();

  const loadProducts = useCallback(async () => {
    try {
      setLoading(true);
      const response = await fetchProductsPaged({
        page,
        size: PAGE_SIZE,
        sort: SORT_MAP[sortBy] || 'name,asc',
        search: searchTerm.trim() || undefined,
        category: categoryFilter.trim() || undefined,
      });
      const data = response.data || {};
      const content = Array.isArray(data.content) ? data.content : [];
      setPageProducts(content);
      setCatalog((prev) => {
        const next = { ...prev };
        content.forEach((p) => { next[p.id] = p; });
        return next;
      });
      setMeta({
        page: data.page ?? page,
        totalPages: data.totalPages ?? 0,
        totalElements: data.totalElements ?? 0,
        first: data.first ?? true,
        last: data.last ?? true,
      });
    } catch (error) {
      console.error('Error fetching products:', error);
      showMessage('❌ Failed to load products', 'error');
      setPageProducts([]);
    } finally {
      setLoading(false);
    }
  }, [page, sortBy, searchTerm, categoryFilter]);

  // Debounce text filters; page and sort changes reload straight away.
  useEffect(() => {
    const timer = setTimeout(loadProducts, 300);
    return () => clearTimeout(timer);
  }, [loadProducts]);

  // Any filter change restarts at the first page.
  useEffect(() => {
    setPage(0);
  }, [searchTerm, categoryFilter, sortBy]);

  const showMessage = (text, type) => {
    setAlert({ show: true, message: text, type });
    setTimeout(() => {
      setAlert({ show: false, message: '', type: '' });
    }, 3000);
  };

  const addItem = (product) => {
    if (!order.orderItems.find(item => item.productId === product.id)) {
      if (product.stockQuantity === 0) {
        showMessage('❌ Product is out of stock', 'error');
        return;
      }
      setOrder({
        ...order,
        orderItems: [...order.orderItems, { 
          productId: product.id, 
          quantity: 1
        }]
      });
      showMessage(`✅ ${product.name} added to order`, 'success');
    } else {
      showMessage('⚠️ Product already in order', 'warning');
    }
  };

  const updateQuantity = (productId, qty) => {
    const quantity = parseInt(qty);
    const product = catalog[productId];
    
    if (quantity < 1) {
      showMessage('❌ Quantity must be at least 1', 'error');
      return;
    }
    
    if (quantity > product.stockQuantity) {
      showMessage(`❌ Only ${product.stockQuantity} units available`, 'error');
      return;
    }
    
    setOrder({
      ...order,
      orderItems: order.orderItems.map(item =>
        item.productId === productId ? { ...item, quantity } : item
      )
    });
  };

  const removeItem = (productId) => {
    const product = catalog[productId];
    setOrder({
      ...order,
      orderItems: order.orderItems.filter(item => item.productId !== productId)
    });
    showMessage(`🗑️ ${product?.name} removed from order`, 'info');
  };

  const getTotalAmount = () => {
    return order.orderItems.reduce((total, item) => {
      const product = catalog[item.productId];
      return total + (product?.price || 0) * item.quantity;
    }, 0);
  };

  const getTotalItems = () => {
    return order.orderItems.reduce((total, item) => total + item.quantity, 0);
  };

  const clearFilters = () => {
    setSearchTerm('');
    setCategoryFilter('');
    setSortBy('name-asc');
  };

  const formatCurrency = (amount) => {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0
    }).format(amount);
  };

  const applyCoupon = async () => {
    if (!couponCode.trim()) return;
    try {
      const res = await validateCoupon(couponCode.trim(), getTotalAmount());
      const data = res.data;
      if (data.valid) {
        setCoupon(data);
        setCouponMessage(`✅ ${data.message} (-${formatCurrency(data.discount)})`);
      } else {
        setCoupon(null);
        setCouponMessage(`❌ ${data.message}`);
      }
    } catch {
      setCoupon(null);
      setCouponMessage('❌ Could not validate coupon');
    }
  };

  const getDiscount = () => (coupon ? coupon.discount : 0);
  const getFinalTotal = () => Math.max(0, getTotalAmount() - getDiscount());

  const handleSubmit = async () => {
    if (order.orderItems.length === 0) {
      showMessage("❌ Please select at least one product", 'error');
      return;
    }

    if (!order.customerName.trim() || !order.customerEmail.trim() || !order.shippingAddress.trim()) {
      showMessage("❌ Please fill in all required fields", 'error');
      return;
    }

    try {
      setSubmitting(true);
      
      const orderData = {
        customerName: order.customerName.trim(),
        customerEmail: order.customerEmail.trim(),
        shippingAddress: order.shippingAddress.trim(),
        orderItems: order.orderItems,
        totalAmount: getTotalAmount(),
        couponCode: coupon ? coupon.code : undefined,
        status: 'PENDING'
      };

      const res = await createOrder(orderData);
      // NOTE: Backend already deducts stock atomically — do NOT call updateProduct here.
      // Phase 9: keep the created order (backend is authoritative for id + total)
      // and hand off to the payment step instead of silently resetting the form.
      setPanelError('');
      setPayment(null);
      setInvoice(null);
      setCreatedOrder(res.data);
    } catch (error) {
      console.error('Error placing order:', error);
      const errorMessage = error.response?.data?.message || 'Order placement failed. Please try again.';
      showMessage(`❌ ${errorMessage}`, 'error');
    } finally {
      setSubmitting(false);
    }
  };

  /* ------------------- Phase 9: payment → invoice handlers ------------------- */

  const handlePayNow = async () => {
    if (!createdOrder || paying) return;
    try {
      setPaying(true);
      setPanelError('');
      // The client only sends the orderId — the backend derives the amount from
      // the stored order, so the browser can never decide the payment amount.
      const res = await payOrder(createdOrder.id, 'MOCK');
      setPayment(res.data);
    } catch (error) {
      console.error('Payment failed:', error);
      setPanelError(error.response?.data?.message || 'Payment failed. Please try again.');
    } finally {
      setPaying(false);
    }
  };

  const handleViewInvoice = async () => {
    if (!createdOrder || invoiceLoading) return;
    try {
      setInvoiceLoading(true);
      setPanelError('');
      const res = await fetchInvoice(createdOrder.id);
      setInvoice(res.data);
    } catch (error) {
      console.error('Invoice fetch failed:', error);
      setPanelError(error.response?.data?.message || 'Could not load the invoice.');
    } finally {
      setInvoiceLoading(false);
    }
  };

  const resetCheckout = () => {
    setCreatedOrder(null);
    setPayment(null);
    setInvoice(null);
    setPanelError('');
    setCouponCode('');
    setCoupon(null);
    setCouponMessage('');
    setOrder({
      customerName: '',
      customerEmail: '',
      shippingAddress: '',
      orderItems: []
    });
    loadProducts();
  };

  const escapeHtml = (value) =>
    String(value ?? '').replace(/[&<>"']/g, (c) => (
      { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));

  // Browser print via a lightweight popup — no PDF library required.
  // Invoice content is the exact backend response, HTML-escaped.
  const handlePrintInvoice = () => {
    if (!invoice) return;
    const printWindow = window.open('', '_blank', 'width=820,height=960');
    if (!printWindow) {
      setPanelError('Please allow pop-ups to print the invoice.');
      return;
    }
    const rows = (invoice.items || []).map((it) => `
      <tr>
        <td>${escapeHtml(it.product)}</td>
        <td style="text-align:right">${escapeHtml(it.quantity)}</td>
        <td style="text-align:right">${escapeHtml(it.unitPrice)}</td>
        <td style="text-align:right">${escapeHtml(it.lineTotal)}</td>
      </tr>`).join('');
    const discountRow = invoice.discount
      ? `<div>Discount${invoice.couponCode ? ` (${escapeHtml(invoice.couponCode)})` : ''}: -${escapeHtml(invoice.discount)}</div>`
      : '';
    printWindow.document.write(`<!doctype html>
<html>
<head>
<title>Invoice ${escapeHtml(invoice.invoiceNumber)}</title>
<style>
  body { font-family: Arial, Helvetica, sans-serif; margin: 40px; color: #111; }
  h1 { font-size: 22px; margin: 0 0 4px; }
  .muted { color: #555; font-size: 12px; margin: 2px 0; }
  .section { margin-top: 14px; font-size: 13px; line-height: 1.6; }
  table { width: 100%; border-collapse: collapse; margin-top: 16px; }
  th, td { border-bottom: 1px solid #ddd; padding: 7px 8px; font-size: 13px; text-align: left; }
  th:last-child, td:last-child { text-align: right; }
  .totals { margin-top: 16px; text-align: right; font-size: 14px; line-height: 1.8; }
  .grand { font-size: 17px; font-weight: bold; }
</style>
</head>
<body>
  <h1>Invoice ${escapeHtml(invoice.invoiceNumber)}</h1>
  <p class="muted">Order #${escapeHtml(invoice.orderId)} &bull; ${escapeHtml(invoice.orderDate)} &bull; Currency: ${escapeHtml(invoice.currency)}</p>
  <div class="section">
    <strong>Customer:</strong> ${escapeHtml(invoice.customer)} (${escapeHtml(invoice.customerEmail)})<br>
    <strong>Shipping address:</strong> ${escapeHtml(invoice.shippingAddress)}<br>
    <strong>Order status:</strong> ${escapeHtml(invoice.status)}
  </div>
  <table>
    <thead>
      <tr><th>Item</th><th style="text-align:right">Qty</th><th style="text-align:right">Unit price</th><th style="text-align:right">Line total</th></tr>
    </thead>
    <tbody>${rows}</tbody>
  </table>
  <div class="totals">
    <div>Subtotal: ${escapeHtml(invoice.subtotal)}</div>
    ${discountRow}
    <div class="grand">Total: ${escapeHtml(invoice.total)} ${escapeHtml(invoice.currency)}</div>
  </div>
</body>
</html>`);
    printWindow.document.close();
    printWindow.focus();
    setTimeout(() => printWindow.print(), 350);
  };

  if (loading && pageProducts.length === 0) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center">
          <div className="w-12 h-12 border-2 border-gray-200 border-t-blue-600 rounded-full mx-auto mb-4 animate-spin"></div>
          <p className="text-gray-600 text-lg font-medium">Loading products...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50 font-sans">
      {alert.show && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
          <div className={`bg-white rounded-lg shadow-2xl max-w-md w-full p-6 transform scale-100 transition-all duration-300 ${
            alert.type === 'success' ? 'border-l-4 border-green-500' :
            alert.type === 'error' ? 'border-l-4 border-red-500' :
            alert.type === 'warning' ? 'border-l-4 border-yellow-500' :
            'border-l-4 border-blue-500'
          }`}>
            <div className="flex justify-between items-start">
              <span className="text-gray-800 font-medium flex-1">{alert.message}</span>
              <button 
                className="text-gray-400 text-xl font-bold hover:text-gray-600 transition-colors ml-4 p-0 leading-none focus:outline-none focus:text-gray-600"
                onClick={() => setAlert({ show: false, message: '', type: '' })}
              >
                ×
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Phase 9: order created → Pay Now step (amount shown comes from the
          backend POST /api/orders response, never from client calculation) */}
      {createdOrder && !payment && !invoice && (
        <div className="fixed inset-0 bg-black bg-opacity-60 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-lg shadow-2xl max-w-lg w-full p-6 text-left">
            <div className="flex items-center mb-4">
              <div className="w-10 h-10 bg-green-100 rounded-full flex items-center justify-center mr-3 text-green-700 text-xl font-bold">
                ✓
              </div>
              <div>
                <h3 className="text-lg font-bold text-gray-900">Order placed successfully</h3>
                <p className="text-sm text-gray-500">Complete the payment to finish checkout.</p>
              </div>
            </div>
            <div className="border border-gray-200 rounded-lg p-4 mb-4 bg-gray-50 space-y-1">
              <div className="flex justify-between text-sm">
                <span className="text-gray-600">Order ID</span>
                <span className="font-semibold text-gray-900">#{createdOrder.id}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-gray-600">Status</span>
                <span className="font-semibold text-gray-900">{createdOrder.status}</span>
              </div>
              {createdOrder.discountAmount > 0 && (
                <div className="flex justify-between text-sm">
                  <span className="text-gray-600">
                    Discount {createdOrder.couponCode ? `(${createdOrder.couponCode})` : ''}
                  </span>
                  <span className="text-green-600">-{formatCurrency(createdOrder.discountAmount)}</span>
                </div>
              )}
              <div className="flex justify-between">
                <span className="text-gray-600">Amount due (server-confirmed)</span>
                <span className="font-bold text-gray-900">{formatCurrency(createdOrder.totalAmount)}</span>
              </div>
            </div>
            {panelError && <p className="text-red-600 text-sm mb-3">{panelError}</p>}
            <div className="flex flex-col gap-2">
              <button
                onClick={handlePayNow}
                disabled={paying}
                className={`w-full px-4 py-3 rounded-lg font-semibold transition-colors cursor-pointer ${
                  paying
                    ? 'bg-gray-200 text-gray-500 cursor-not-allowed'
                    : 'bg-green-600 text-white hover:bg-green-700'
                }`}
              >
                {paying ? (
                  <span className="inline-flex items-center justify-center">
                    <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin mr-2"></span>
                    Processing payment...
                  </span>
                ) : (
                  `Pay Now • ${formatCurrency(createdOrder.totalAmount)}`
                )}
              </button>
              <div className="flex justify-between gap-2">
                <button
                  onClick={() => navigate('/user/orders')}
                  className="flex-1 px-3 py-2 rounded-lg border border-gray-300 text-gray-700 text-sm hover:bg-gray-50 transition-colors cursor-pointer"
                >
                  View My Orders
                </button>
                <button
                  onClick={resetCheckout}
                  className="flex-1 px-3 py-2 rounded-lg border border-gray-300 text-gray-700 text-sm hover:bg-gray-50 transition-colors cursor-pointer"
                >
                  Start New Order
                </button>
              </div>
            </div>
            <p className="text-xs text-gray-400 mt-3 text-center">
              Mock payment gateway — the amount is confirmed by the server.
            </p>
          </div>
        </div>
      )}

      {/* Phase 9: payment success → confirmation with invoice / my-orders actions */}
      {createdOrder && payment && !invoice && (
        <div className="fixed inset-0 bg-black bg-opacity-60 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-lg shadow-2xl max-w-lg w-full p-6 text-left">
            <div className="flex items-center mb-4">
              <div className="w-10 h-10 bg-green-100 rounded-full flex items-center justify-center mr-3 text-green-700 text-xl font-bold">
                ✓
              </div>
              <div>
                <h3 className="text-lg font-bold text-gray-900">Payment successful</h3>
                <p className="text-sm text-gray-500">Order #{createdOrder.id} has been paid.</p>
              </div>
            </div>
            <div className="border border-gray-200 rounded-lg p-4 mb-4 bg-gray-50 space-y-1">
              <div className="flex justify-between text-sm">
                <span className="text-gray-600">Amount paid</span>
                <span className="font-bold text-gray-900">{formatCurrency(payment.amount)}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-gray-600">Payment status</span>
                <span className="font-semibold text-gray-900">{payment.status}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-gray-600">Transaction ref</span>
                <span className="font-mono text-gray-900">{payment.transactionRef}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-gray-600">Invoice no.</span>
                <span className="font-mono text-gray-900">{payment.invoiceNumber}</span>
              </div>
            </div>
            {panelError && <p className="text-red-600 text-sm mb-3">{panelError}</p>}
            <div className="flex flex-col gap-2">
              <button
                onClick={handleViewInvoice}
                disabled={invoiceLoading}
                className={`w-full px-4 py-3 rounded-lg font-semibold transition-colors cursor-pointer ${
                  invoiceLoading
                    ? 'bg-gray-200 text-gray-500 cursor-not-allowed'
                    : 'bg-blue-600 text-white hover:bg-blue-700'
                }`}
              >
                {invoiceLoading ? 'Loading invoice...' : 'View Invoice'}
              </button>
              <div className="flex justify-between gap-2">
                <button
                  onClick={() => navigate('/user/orders')}
                  className="flex-1 px-3 py-2 rounded-lg border border-gray-300 text-gray-700 text-sm hover:bg-gray-50 transition-colors cursor-pointer"
                >
                  View My Orders
                </button>
                <button
                  onClick={resetCheckout}
                  className="flex-1 px-3 py-2 rounded-lg border border-gray-300 text-gray-700 text-sm hover:bg-gray-50 transition-colors cursor-pointer"
                >
                  Start New Order
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
      {/* Phase 9: invoice modal — exact backend GET /api/payments/invoice/{id} response */}
      {invoice && (
        <div className="fixed inset-0 bg-black bg-opacity-70 flex items-center justify-center z-[60] p-4">
          <div className="bg-white rounded-lg shadow-2xl max-w-2xl w-full p-6 text-left max-h-[90vh] overflow-y-auto">
            <div className="flex justify-between items-start mb-4">
              <div>
                <h3 className="text-lg font-bold text-gray-900">
                  Invoice {invoice.invoiceNumber}
                </h3>
                <p className="text-sm text-gray-500">
                  Order #{invoice.orderId} • {invoice.orderDate} • Currency: {invoice.currency}
                </p>
              </div>
              <button
                onClick={() => setInvoice(null)}
                className="text-gray-400 text-xl font-bold hover:text-gray-600 leading-none cursor-pointer"
                aria-label="Close invoice"
              >
                ×
              </button>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-1 text-sm mb-4">
              <p><span className="text-gray-500">Customer:</span> <span className="text-gray-900">{invoice.customer}</span></p>
              <p><span className="text-gray-500">Email:</span> <span className="text-gray-900">{invoice.customerEmail}</span></p>
              <p className="sm:col-span-2"><span className="text-gray-500">Shipping:</span> <span className="text-gray-900">{invoice.shippingAddress}</span></p>
              <p><span className="text-gray-500">Order status:</span> <span className="text-gray-900">{invoice.status}</span></p>
            </div>

            <table className="w-full text-sm text-left mb-4">
              <thead>
                <tr className="text-gray-500 text-xs uppercase border-b border-gray-200">
                  <th className="py-2 pr-3">Item</th>
                  <th className="py-2 pr-3 text-right">Qty</th>
                  <th className="py-2 pr-3 text-right">Unit price</th>
                  <th className="py-2 text-right">Line total</th>
                </tr>
              </thead>
              <tbody className="text-gray-800">
                {(invoice.items || []).map((it, idx) => (
                  <tr key={idx} className="border-b border-gray-100">
                    <td className="py-2 pr-3">{it.product}</td>
                    <td className="py-2 pr-3 text-right">{it.quantity}</td>
                    <td className="py-2 pr-3 text-right">{formatCurrency(it.unitPrice)}</td>
                    <td className="py-2 text-right">{formatCurrency(it.lineTotal)}</td>
                  </tr>
                ))}
              </tbody>
            </table>

            <div className="border-t border-gray-200 pt-3 space-y-1 text-sm">
              <div className="flex justify-between">
                <span className="text-gray-500">Subtotal</span>
                <span className="text-gray-900">{formatCurrency(invoice.subtotal)}</span>
              </div>
              {invoice.discount > 0 && (
                <div className="flex justify-between">
                  <span className="text-gray-500">
                    Discount {invoice.couponCode ? `(${invoice.couponCode})` : ''}
                  </span>
                  <span className="text-green-600">-{formatCurrency(invoice.discount)}</span>
                </div>
              )}
              <div className="flex justify-between border-t border-gray-200 pt-2">
                <span className="font-bold text-gray-900">Total</span>
                <span className="font-bold text-gray-900">
                  {formatCurrency(invoice.total)} {invoice.currency}
                </span>
              </div>
            </div>

            {payment && (
              <div className="mt-4 border border-gray-200 rounded-lg p-3 bg-gray-50 text-sm space-y-1">
                <p className="font-semibold text-gray-900">Payment information</p>
                <div className="flex justify-between">
                  <span className="text-gray-500">Method</span>
                  <span className="text-gray-900">{payment.method}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-500">Status</span>
                  <span className="text-gray-900">{payment.status}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-500">Transaction ref</span>
                  <span className="font-mono text-gray-900">{payment.transactionRef}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-500">Paid at</span>
                  <span className="text-gray-900">{payment.createdAt}</span>
                </div>
              </div>
            )}

            {panelError && <p className="text-red-600 text-sm mt-3">{panelError}</p>}

            <div className="flex justify-end gap-2 mt-5">
              <button
                onClick={handlePrintInvoice}
                className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors cursor-pointer"
              >
                Print Invoice
              </button>
              <button
                onClick={() => setInvoice(null)}
                className="px-4 py-2 rounded-lg border border-gray-300 text-gray-700 text-sm hover:bg-gray-50 transition-colors cursor-pointer"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="max-w-7xl mx-auto px-4 py-8 sm:px-6 lg:px-8">
        <div className="bg-white rounded-lg shadow-sm p-6 mb-8">
          <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4">
            <div>
              <h1 className="text-3xl font-bold text-gray-900 mb-1 leading-tight">Place New Order</h1>
              <p className="text-gray-600">Add products and customer details to place an order</p>
            </div>
            <div className="flex gap-3">
              <div className="px-4 py-2 bg-blue-50 text-blue-800 rounded-full font-semibold text-sm">
                {getTotalItems()} items
              </div>
              <div className="px-4 py-2 bg-green-50 text-green-800 rounded-full font-semibold text-sm">
                {formatCurrency(getTotalAmount())}
              </div>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-lg shadow-sm p-6 mb-8">
          <div className="flex items-center mb-6">
            <div className="w-8 h-8 bg-blue-50 rounded-full flex items-center justify-center mr-3">
              <span className="text-blue-600 font-bold text-sm">1</span>
            </div>
            <h2 className="text-xl font-semibold text-gray-900">Customer Information</h2>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
            <div className="flex flex-col">
              <label className="block text-sm font-medium text-gray-700 mb-2">Customer Name *</label>
              <input
                type="text"
                value={order.customerName}
                onChange={e => setOrder({ ...order, customerName: e.target.value })}
                className="w-full px-4 py-3 border border-gray-300 rounded-lg text-sm transition-all duration-200 focus:outline-none focus:border-blue-600 focus:ring-3 focus:ring-blue-100 bg-white placeholder-gray-400"
                placeholder="Enter customer name"
                required
              />
            </div>
            <div className="flex flex-col">
              <label className="block text-sm font-medium text-gray-700 mb-2">Customer Email *</label>
              <input
                type="email"
                value={order.customerEmail}
                onChange={e => setOrder({ ...order, customerEmail: e.target.value })}
                className="w-full px-4 py-3 border border-gray-300 rounded-lg text-sm transition-all duration-200 focus:outline-none focus:border-blue-600 focus:ring-3 focus:ring-blue-100 bg-white placeholder-gray-400"
                placeholder="customer@example.com"
                required
              />
            </div>
            <div className="flex flex-col sm:col-span-2">
              <label className="block text-sm font-medium text-gray-700 mb-2">Shipping Address *</label>
              <textarea
                value={order.shippingAddress}
                onChange={e => setOrder({ ...order, shippingAddress: e.target.value })}
                className="w-full px-4 py-3 border border-gray-300 rounded-lg text-sm transition-all duration-200 focus:outline-none focus:border-blue-600 focus:ring-3 focus:ring-blue-100 bg-white resize-vertical placeholder-gray-400 font-sans"
                rows="3"
                placeholder="Enter complete shipping address"
                required
              />
            </div>
          </div>
        </div>

        <div className="bg-white rounded-lg shadow-sm p-6 mb-8">
          <div className="flex items-center mb-6">
            <div className="w-8 h-8 bg-blue-50 rounded-full flex items-center justify-center mr-3">
              <span className="text-blue-600 font-bold text-sm">2</span>
            </div>
            <h2 className="text-xl font-semibold text-gray-900">Select Products</h2>
          </div>
          
          <div className="bg-gray-50 rounded-lg p-4 mb-6">
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
              <input
                type="text"
                placeholder="Search products..."
                value={searchTerm}
                onChange={e => setSearchTerm(e.target.value)}
                className="px-3 py-2 border border-gray-300 rounded-lg text-sm bg-white transition-all duration-200 focus:outline-none focus:border-blue-600 focus:ring-3 focus:ring-blue-100 placeholder-gray-400"
              />
              <input
                type="text"
                placeholder="Filter by category..."
                value={categoryFilter}
                onChange={e => setCategoryFilter(e.target.value)}
                className="px-3 py-2 border border-gray-300 rounded-lg text-sm bg-white transition-all duration-200 focus:outline-none focus:border-blue-600 focus:ring-3 focus:ring-blue-100 placeholder-gray-400"
              />
              <select 
                value={sortBy} 
                onChange={e => setSortBy(e.target.value)}
                className="px-3 py-2 border border-gray-300 rounded-lg text-sm bg-white transition-all duration-200 focus:outline-none focus:border-blue-600 focus:ring-3 focus:ring-blue-100 cursor-pointer"
              >
                <option value="name-asc">Name (A-Z)</option>
                <option value="name-desc">Name (Z-A)</option>
                <option value="price-asc">Price (Low-High)</option>
                <option value="price-desc">Price (High-Low)</option>
                <option value="stock-asc">Stock (Low-High)</option>
                <option value="stock-desc">Stock (High-Low)</option>
              </select>
              <button 
                type="button" 
                onClick={clearFilters} 
                className="px-4 py-2 bg-red-500 text-white rounded-lg font-medium cursor-pointer transition-colors duration-200 hover:bg-red-600 focus:outline-none focus:ring-3 focus:ring-red-100"
              >
                Clear Filters
              </button>
            </div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-6">
            {pageProducts.length > 0 ? (
              pageProducts.map(product => (
                <div key={product.id} className="border border-gray-200 rounded-lg p-6 bg-white transition-all duration-200 hover:shadow-lg hover:-translate-y-0.5">
                  <div className="mb-4">
                    <h3 className="text-lg font-semibold text-gray-900 mb-2">{product.name}</h3>
                    <p className="text-gray-600 text-sm mb-3 line-clamp-2 leading-6 overflow-hidden" style={{
                      display: '-webkit-box',
                      WebkitLineClamp: 2,
                      WebkitBoxOrient: 'vertical'
                    }}>{product.description}</p>
                    <div className="flex flex-wrap gap-2 mb-4">
                      <span className="px-3 py-1 bg-green-50 text-green-800 rounded-full text-sm font-medium">
                        {formatCurrency(product.price)}
                      </span>
                      <span className="px-3 py-1 bg-blue-50 text-blue-800 rounded-full text-sm font-medium">
                        {product.category}
                      </span>
                      <span className={`px-3 py-1 rounded-full text-sm font-medium ${
                        product.stockQuantity === 0 
                          ? 'bg-red-50 text-red-800' 
                          : 'bg-gray-50 text-gray-700'
                      }`}>
                        {product.stockQuantity === 0 ? 'Out of Stock' : `${product.stockQuantity} in stock`}
                      </span>
                    </div>
                  </div>
                  <div className="flex justify-end">
                    {product.stockQuantity > 0 ? (
                      <button 
                        type="button" 
                        onClick={() => addItem(product)}
                        disabled={order.orderItems.some(item => item.productId === product.id)}
                        className={`px-4 py-2 rounded-lg font-medium text-sm cursor-pointer transition-all duration-200 inline-flex items-center justify-center focus:outline-none focus:ring-3 ${
                          order.orderItems.some(item => item.productId === product.id)
                            ? 'bg-gray-100 text-gray-600 cursor-not-allowed focus:ring-gray-100'
                            : 'bg-blue-600 text-white hover:bg-blue-700 hover:shadow-md hover:-translate-y-px focus:ring-blue-100'
                        }`}
                      >
                        {order.orderItems.some(item => item.productId === product.id) ? 'Added ✓' : 'Add to Order'}
                      </button>
                    ) : (
                      <button type="button" className="px-4 py-2 bg-gray-100 text-gray-400 rounded-lg font-medium text-sm cursor-not-allowed" disabled>
                        Out of Stock
                      </button>
                    )}
                  </div>
                </div>
              ))
            ) : (
              <div className="col-span-full text-center py-12">
                <div className="text-6xl mb-4">📦</div>
                <h3 className="text-lg font-medium text-gray-900 mb-2">No products found</h3>
                <p className="text-gray-600 mb-4">
                  {meta.totalElements === 0 && !searchTerm && !categoryFilter
                    ? 'No products available in the database'
                    : 'Try adjusting your search or filters'}
                </p>
                {meta.totalElements === 0 && !searchTerm && !categoryFilter && (
                  <button 
                    onClick={loadProducts}
                    className="px-4 py-2 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700 transition-colors duration-200 focus:outline-none focus:ring-3 focus:ring-blue-100"
                  >
                    Retry Loading
                  </button>
                )}
              </div>
            )}
          </div>

          {/* Server-side pagination */}
          <div className="flex flex-col sm:flex-row items-center justify-between gap-4 mt-6 pt-4 border-t border-gray-200">
            <p className="text-sm text-gray-600">
              Page {Math.min(meta.page + 1, Math.max(meta.totalPages, 1))} of {Math.max(meta.totalPages, 1)}
            </p>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={meta.first || loading}
                className="px-3 py-2 border border-gray-300 rounded-lg text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                Previous
              </button>
              <button
                type="button"
                onClick={() => setPage((p) => p + 1)}
                disabled={meta.last || loading}
                className="px-3 py-2 border border-gray-300 rounded-lg text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                Next
              </button>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-lg shadow-sm p-6 mb-8">
          <div className="flex items-center mb-6">
            <div className="w-8 h-8 bg-blue-50 rounded-full flex items-center justify-center mr-3">
              <span className="text-blue-600 font-bold text-sm">3</span>
            </div>
            <h2 className="text-xl font-semibold text-gray-900">Order Summary ({order.orderItems.length} items)</h2>
          </div>
          
          {order.orderItems.length === 0 ? (
            <div className="text-center py-12">
              <div className="text-6xl mb-4">🛒</div>
              <h3 className="text-lg font-medium text-gray-900 mb-2">No items selected yet</h3>
              <p className="text-gray-600">Add products from above to place your order</p>
            </div>
          ) : (
            <div className="flex flex-col gap-4">
              {order.orderItems.map(item => {
                const product = catalog[item.productId];
                if (!product) return null;
                
                return (
                  <div key={item.productId} className="bg-gray-50 rounded-lg p-4">
                    <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
                      <div className="flex-1">
                        <h4 className="font-semibold text-gray-900 mb-1">{product.name}</h4>
                        <p className="text-gray-600 text-sm mb-2">{product.description}</p>
                        <span className="text-green-600 font-medium">{formatCurrency(product.price)} each</span>
                      </div>
                      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-4">
                        <div className="flex items-center gap-2">
                          <label className="text-sm font-medium text-gray-700">Qty:</label>
                          <input
                            type="number"
                            min="1"
                            max={product.stockQuantity}
                            value={item.quantity}
                            onChange={e => updateQuantity(item.productId, e.target.value)}
                            className="w-20 px-2 py-1 border border-gray-300 rounded text-center text-sm focus:outline-none focus:border-blue-600 focus:ring-3 focus:ring-blue-100 transition-all duration-200"
                          />
                          <span className="text-xs text-gray-600">max: {product.stockQuantity}</span>
                        </div>
                        <div className="font-semibold text-gray-900 ml-auto sm:ml-0">
                          {formatCurrency(product.price * item.quantity)}
                        </div>
                        <button 
                          type="button" 
                          onClick={() => removeItem(item.productId)}
                          className="px-3 py-1 bg-red-500 text-white rounded text-xs font-medium cursor-pointer transition-colors duration-200 hover:bg-red-600 focus:outline-none focus:ring-3 focus:ring-red-100"
                        >
                          Remove
                        </button>
                      </div>
                    </div>
                  </div>
                );
              })}
              
              <div className="border-t-2 border-gray-200 pt-4 mt-6 space-y-3">
                <div className="flex flex-col sm:flex-row gap-3 sm:items-center">
                  <input
                    type="text"
                    value={couponCode}
                    onChange={(e) => setCouponCode(e.target.value.toUpperCase())}
                    placeholder="Coupon code (optional)"
                    className="flex-1 px-3 py-2 border border-gray-300 rounded-md uppercase"
                  />
                  <button
                    type="button"
                    onClick={applyCoupon}
                    className="px-4 py-2 bg-indigo-600 text-white rounded-md font-medium hover:bg-indigo-700"
                  >
                    Apply
                  </button>
                </div>
                {couponMessage && (
                  <p className={`text-sm ${coupon ? 'text-green-600' : 'text-red-500'}`}>{couponMessage}</p>
                )}
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center">
                  <span className="text-lg font-semibold text-gray-700">Subtotal:</span>
                  <span className="text-lg font-medium text-gray-700">{formatCurrency(getTotalAmount())}</span>
                </div>
                {coupon && (
                  <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center">
                    <span className="text-lg font-semibold text-gray-700">Discount ({coupon.code}):</span>
                    <span className="text-lg font-medium text-red-500">-{formatCurrency(getDiscount())}</span>
                  </div>
                )}
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center border-t border-gray-200 pt-2">
                  <span className="text-xl font-bold text-gray-900">Total:</span>
                  <span className="text-2xl font-bold text-green-600">{formatCurrency(getFinalTotal())}</span>
                </div>
              </div>
            </div>
          )}
        </div>

        <div className="text-center">
          <button 
            onClick={handleSubmit}
            disabled={order.orderItems.length === 0 || submitting}
            className={`px-8 py-4 rounded-lg font-semibold text-lg cursor-pointer transition-all duration-200 inline-flex items-center justify-center min-w-[200px] focus:outline-none focus:ring-3 ${
              order.orderItems.length === 0 || submitting
                ? 'bg-gray-100 text-gray-400 cursor-not-allowed focus:ring-gray-100'
                : 'bg-green-600 text-white hover:bg-green-700 hover:shadow-md hover:-translate-y-px focus:ring-green-100'
            }`}
          >
            {submitting ? (
              <>
                <div className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin mr-2"></div>
                Processing...
              </>
            ) : (
              `Place Order • ${formatCurrency(getFinalTotal())}`
            )}
          </button>
        </div>
      </div>
    </div>
  );
};

export default PlaceOrder;