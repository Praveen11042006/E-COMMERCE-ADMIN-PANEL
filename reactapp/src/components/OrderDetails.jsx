import React, { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  getOrder,
  updateOrderStatus,
  deleteOrder,
  fetchPaymentsForOrder,
  payOrder,
  fetchInvoice,
} from '../utils/api';
import {
  Package,
  Truck,
  CheckCircle,
  Trash2,
  ArrowLeft,
  Save,
  Edit3,
  User,
  Mail,
  MapPin,
  Calendar,
  DollarSign,
  Clock,
  AlertCircle,
  FileText,
  Eye,
  RefreshCw,
  Download,
  Printer,
  Send,
  X,
  Plus,
  Minus,
  ShoppingCart,
  CreditCard,
  Archive,
  Bell,
  Settings
} from 'lucide-react';

export default function OrderDetails({ orderId: propOrderId, onBack }) {
  const params = useParams();
  const navigate = useNavigate();
  const orderId = propOrderId ?? params.id;

  // State management
  const [order, setOrder] = useState(null);
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [successMessage, setSuccessMessage] = useState('');

  // Phase 13B: payment + invoice data come from the real /api/payments endpoints.
  const [payments, setPayments] = useState([]);
  const [paymentLoading, setPaymentLoading] = useState(false);
  const [paying, setPaying] = useState(false);
  const [invoice, setInvoice] = useState(null);
  const [invoiceLoading, setInvoiceLoading] = useState(false);

  // Helper: accept axios response or plain object
  const extractData = useCallback((resp) => {
    if (!resp) return resp;
    if (resp.data !== undefined) return resp.data;
    return resp;
  }, []);

  // Load order details
  const loadOrder = useCallback(async () => {
    if (!orderId) {
      setError('Order ID is required');
      setLoading(false);
      return;
    }

    try {
      setLoading(true);
      setError('');
      const resp = await getOrder(orderId);
      const data = extractData(resp);
      
      if (!data) {
        setError('Order not found');
        setOrder(null);
        return;
      }

      // Normalize order data
      const normalizedOrder = {
        ...data,
        orderItems: Array.isArray(data.orderItems) ? data.orderItems : [],
        totalAmount: parseFloat(data.totalAmount) || 0
      };

      setOrder(normalizedOrder);
      setStatus(normalizedOrder.status || 'PENDING');

      // Payment records are supplementary: a failure here must never hide the order.
      try {
        setPaymentLoading(true);
        const paymentResp = await fetchPaymentsForOrder(orderId);
        setPayments(Array.isArray(paymentResp.data) ? paymentResp.data : []);
      } catch (paymentErr) {
        console.error('loadPayments error', paymentErr);
        setPayments([]);
      } finally {
        setPaymentLoading(false);
      }
    } catch (err) {
      console.error('loadOrder error', err);
      setError('Failed to load order details. Please try again.');
      setOrder(null);
    } finally {
      setLoading(false);
    }
  }, [orderId, extractData]);

  // Initial load
  useEffect(() => {
    loadOrder();
  }, [loadOrder]);

  // Auto-clear messages
  useEffect(() => {
    if (successMessage) {
      const timer = setTimeout(() => setSuccessMessage(''), 4000);
      return () => clearTimeout(timer);
    }
  }, [successMessage]);

  useEffect(() => {
    if (error) {
      const timer = setTimeout(() => setError(''), 6000);
      return () => clearTimeout(timer);
    }
  }, [error]);

  // Handle status update
  const handleSave = async () => {
    if (!orderId || status === order?.status) return;

    try {
      setSaving(true);
      await updateOrderStatus(orderId, status);
      
      // Update local state
      setOrder((prev) => (prev ? { ...prev, status } : prev));
      setSuccessMessage(`Order status updated to ${status} successfully`);
    } catch (err) {
      console.error('updateOrderStatus error', err);
      setError('Failed to update order status. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  // Handle order deletion
  const handleDelete = async () => {
    if (!window.confirm('Are you sure you want to delete this order? This action cannot be undone.')) {
      return;
    }
    
    try {
      setSaving(true);
      await deleteOrder(orderId);
      setSuccessMessage('Order deleted successfully');
      
      // Navigate back after short delay to show message
      setTimeout(() => {
        if (onBack) onBack();
        else navigate('/orders');
      }, 1500);
    } catch (err) {
      console.error('deleteOrder error', err);
      setError('Failed to delete order. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  // Mock payment: the backend derives the amount from the order and has no gateway.
  const handlePay = async () => {
    try {
      setPaying(true);
      setError('');
      await payOrder(order.id);
      const paymentResp = await fetchPaymentsForOrder(order.id);
      setPayments(Array.isArray(paymentResp.data) ? paymentResp.data : []);
      setSuccessMessage('Mock payment recorded for this order');
    } catch (err) {
      console.error('payOrder error', err);
      setError(err.response?.data?.message || 'Payment could not be completed. Please try again.');
    } finally {
      setPaying(false);
    }
  };

  // Invoice is generated server-side from the stored order and payment.
  const handleInvoice = async () => {
    try {
      setInvoiceLoading(true);
      setError('');
      const resp = await fetchInvoice(order.id);
      setInvoice(resp.data);
    } catch (err) {
      console.error('fetchInvoice error', err);
      setError(err.response?.data?.message || 'Invoice could not be loaded. Please try again.');
    } finally {
      setInvoiceLoading(false);
    }
  };

  // Handle back navigation
  const handleBack = useCallback(() => {
    if (onBack) onBack();
    else navigate('/admin/orders');
  }, [onBack, navigate]);

  // Get status badge styling
  const getStatusBadge = (orderStatus) => {
    const baseClasses = "px-4 py-2 rounded-full text-sm font-semibold uppercase tracking-wider";
    switch (orderStatus) {
      case 'PENDING':
        return `${baseClasses} bg-yellow-100 text-yellow-800 border border-yellow-200`;
      case 'SHIPPED':
        return `${baseClasses} bg-blue-100 text-blue-800 border border-blue-200`;
      case 'DELIVERED':
        return `${baseClasses} bg-green-100 text-green-800 border border-green-200`;
      case 'CANCELLED':
        return `${baseClasses} bg-red-100 text-red-800 border border-red-200`;
      default:
        return `${baseClasses} bg-gray-100 text-gray-800 border border-gray-200`;
    }
  };

  // Calculate order totals from stored line items. The backend does not model tax or
  // shipping, so neither is displayed (totalAmount is the authoritative payable amount).
  const calculateTotals = useCallback((items) => {
    const subtotal = items.reduce((sum, item) => {
      const price = parseFloat(item.priceAtPurchase || item.price || 0);
      const quantity = parseInt(item.quantity || item.qty || 1);
      return sum + (price * quantity);
    }, 0);

    return { subtotal };
  }, []);

  // Print order
  const handlePrint = () => {
    window.print();
    setSuccessMessage('Print dialog opened');
  };

  // Export order details
  const handleExport = () => {
    if (!order) return;

    const orderData = {
      orderId: order.id,
      customerName: order.customerName,
      customerEmail: order.customerEmail,
      status: order.status,
      totalAmount: order.totalAmount,
      orderDate: order.orderDate,
      items: order.orderItems?.map(item => ({
        product: item.product?.name || 'Product',
        quantity: item.quantity || 1,
        price: item.priceAtPurchase || item.price || 0
      }))
    };

    const dataStr = JSON.stringify(orderData, null, 2);
    const dataBlob = new Blob([dataStr], { type: 'application/json' });
    const url = URL.createObjectURL(dataBlob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `order-${order.id}-details.json`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    setSuccessMessage('Order details exported successfully');
  };

  // Quick status change handlers
  const handleQuickStatusChange = async (newStatus) => {
    if (newStatus === order?.status) return;
    setStatus(newStatus);
    
    try {
      setSaving(true);
      await updateOrderStatus(orderId, newStatus);
      setOrder((prev) => (prev ? { ...prev, status: newStatus } : prev));
      setSuccessMessage(`Order status updated to ${newStatus} successfully`);
    } catch (err) {
      console.error('Quick status update error', err);
      setError('Failed to update order status. Please try again.');
      setStatus(order?.status || ''); // Revert on error
    } finally {
      setSaving(false);
    }
  };

  // Loading state
  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-gray-50 to-blue-50">
        <div className="flex flex-col items-center gap-4">
          <div className="w-12 h-12 border-4 border-blue-200 border-t-blue-600 rounded-full animate-spin"></div>
          <p className="text-gray-600 text-lg font-medium">Loading order details...</p>
          <p className="text-gray-500 text-sm">Please wait while we fetch the information</p>
        </div>
      </div>
    );
  }

  // Error state
  if (error && !order) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-gray-50 to-blue-50">
        <div className="max-w-3xl mx-auto pt-12 px-6">
          <div className="bg-white rounded-xl shadow-sm border p-8 text-center">
            <AlertCircle className="w-16 h-16 text-red-500 mx-auto mb-4" />
            <h3 className="text-xl font-semibold text-gray-900 mb-2">Order Not Found</h3>
            <p className="text-red-600 mb-6">{error}</p>
            <div className="flex gap-4 justify-center">
              <button 
                onClick={handleBack}
                className="flex items-center gap-2 px-6 py-3 bg-gray-200 text-gray-700 rounded-lg hover:bg-gray-300 transition-colors"
              >
                <ArrowLeft className="w-4 h-4" />
                Back to Orders
              </button>
              <button 
                onClick={loadOrder}
                className="flex items-center gap-2 px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
              >
                <RefreshCw className="w-4 h-4" />
                Try Again
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // No order state
  if (!order) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-gray-50 to-blue-50">
        <div className="max-w-3xl mx-auto pt-12 px-6">
          <div className="bg-white rounded-xl shadow-sm border p-8 text-center">
            <Package className="w-16 h-16 text-gray-400 mx-auto mb-4" />
            <h3 className="text-xl font-semibold text-gray-900 mb-2">Order Not Available</h3>
            <p className="text-gray-600 mb-6">The requested order could not be found.</p>
            <button 
              onClick={handleBack}
              className="flex items-center gap-2 px-6 py-3 bg-gray-200 text-gray-700 rounded-lg hover:bg-gray-300 transition-colors mx-auto"
            >
              <ArrowLeft className="w-4 h-4" />
              Back to Orders
            </button>
          </div>
        </div>
      </div>
    );
  }

  const items = order.orderItems || [];
  const totals = calculateTotals(items);
  const latestPayment = [...payments].sort((a, b) => (a.id || 0) - (b.id || 0)).slice(-1)[0] || null;
  const hasSuccessfulPayment = payments.some((p) => p.status === 'SUCCESS');
  const paymentStatusClass = (paymentStatus) => {
    if (paymentStatus === 'SUCCESS') return 'bg-green-100 text-green-800';
    if (paymentStatus === 'FAILED') return 'bg-red-100 text-red-800';
    return 'bg-yellow-100 text-yellow-800';
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-gray-50 to-blue-50">
      {/* Header */}
      <div className="bg-white shadow-sm border-b sticky top-0 z-10">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center py-6">
            <div className="flex items-center gap-4">
              <button
                onClick={handleBack}
                className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
              >
                <ArrowLeft className="w-5 h-5" />
              </button>
              <Package className="w-8 h-8 text-blue-600" />
              <div>
                <h1 className="text-2xl font-bold text-gray-900">Order Details</h1>
                <p className="text-gray-600">#{order.id}</p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <button
                onClick={loadOrder}
                disabled={loading || saving}
                className="flex items-center gap-2 px-4 py-2 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors disabled:opacity-50"
              >
                <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
                Refresh
              </button>
              <button
                onClick={handlePrint}
                className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
              >
                <Printer className="w-4 h-4" />
                Print
              </button>
              <button
                onClick={handleExport}
                className="flex items-center gap-2 px-4 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 transition-colors"
              >
                <Download className="w-4 h-4" />
                Export
              </button>
            </div>
          </div>
        </div>
      </div>

      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {/* Messages */}
        {successMessage && (
          <div className="mb-6 bg-green-50 border border-green-200 rounded-lg p-4 flex items-center gap-3">
            <CheckCircle className="w-5 h-5 text-green-600" />
            <span className="text-green-800 font-medium">{successMessage}</span>
          </div>
        )}

        {error && (
          <div className="mb-6 bg-red-50 border border-red-200 rounded-lg p-4 flex items-center gap-3">
            <AlertCircle className="w-5 h-5 text-red-600" />
            <span className="text-red-800 font-medium">{error}</span>
          </div>
        )}

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
          {/* Main Order Details */}
          <div className="lg:col-span-2 space-y-6">
            {/* Order Header */}
            <div className="bg-white rounded-xl shadow-sm border p-6">
              <div className="flex justify-between items-start mb-6">
                <div>
                  <h2 className="text-2xl font-bold text-gray-900 mb-2">Order #{order.id}</h2>
                  <div className="flex items-center gap-3">
                    <span className={getStatusBadge(order.status)}>{order.status}</span>
                    <div className="flex items-center gap-2 text-gray-600">
                      <Calendar className="w-4 h-4" />
                      <span className="text-sm">
                        {order.orderDate ? new Date(order.orderDate).toLocaleDateString('en-GB', {
                          year: 'numeric',
                          month: 'long',
                          day: 'numeric',
                          hour: '2-digit',
                          minute: '2-digit'
                        }) : '—'}
                      </span>
                    </div>
                  </div>
                </div>
                <div className="text-right">
                  <p className="text-3xl font-bold text-green-600">₹{order.totalAmount.toFixed(2)}</p>
                  <p className="text-gray-600 text-sm">{items.length} items</p>
                </div>
              </div>

              {/* Customer Information */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6 pt-6 border-t border-gray-100">
                <div>
                  <h3 className="font-semibold text-gray-900 mb-4 flex items-center gap-2">
                    <User className="w-5 h-5 text-blue-600" />
                    Customer Information
                  </h3>
                  <div className="space-y-3">
                    <div className="flex items-center gap-3">
                      <User className="w-4 h-4 text-gray-400" />
                      <span className="font-medium text-gray-900">{order.customerName || 'N/A'}</span>
                    </div>
                    <div className="flex items-center gap-3">
                      <Mail className="w-4 h-4 text-gray-400" />
                      <span className="text-gray-700">{order.customerEmail || 'N/A'}</span>
                    </div>
                  </div>
                </div>

                <div>
                  <h3 className="font-semibold text-gray-900 mb-4 flex items-center gap-2">
                    <MapPin className="w-5 h-5 text-green-600" />
                    Shipping Information
                  </h3>
                  <div className="space-y-3">
                    <div className="flex items-start gap-3">
                      <MapPin className="w-4 h-4 text-gray-400 mt-1" />
                      <span className="text-gray-700">{order.shippingAddress || 'N/A'}</span>
                    </div>
                  </div>
                </div>
              </div>

              {/* Payment — real records from /api/payments (mock gateway, no real charge) */}
              <div className="pt-6 border-t border-gray-100">
                <div className="flex flex-wrap items-center justify-between gap-2 mb-4">
                  <h3 className="font-semibold text-gray-900 flex items-center gap-2">
                    <CreditCard className="w-5 h-5 text-purple-600" />
                    Payment
                  </h3>
                  <span className="text-xs text-gray-500">Mock gateway — no real payment is processed</span>
                </div>

                {paymentLoading ? (
                  <p className="text-sm text-gray-500">Loading payment records...</p>
                ) : payments.length === 0 ? (
                  <p className="text-sm text-gray-600">No payment recorded for this order.</p>
                ) : (
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <div className="flex items-center gap-3">
                      <span className="text-gray-600">Status:</span>
                      <span className={`px-3 py-1 rounded-full text-xs font-semibold uppercase ${paymentStatusClass(latestPayment.status)}`}>
                        {latestPayment.status}
                      </span>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className="text-gray-600">Method:</span>
                      <span className="font-medium text-gray-900">{latestPayment.method || 'MOCK'}</span>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className="text-gray-600">Amount:</span>
                      <span className="font-medium text-gray-900">₹{Number(latestPayment.amount || 0).toFixed(2)}</span>
                    </div>
                    {latestPayment.invoiceNumber && (
                      <div className="flex items-center gap-3">
                        <span className="text-gray-600">Invoice:</span>
                        <span className="font-mono text-sm text-gray-900">{latestPayment.invoiceNumber}</span>
                      </div>
                    )}
                    {latestPayment.transactionRef && (
                      <div className="flex items-center gap-3 md:col-span-2">
                        <span className="text-gray-600">Reference:</span>
                        <span className="font-mono text-sm text-gray-700">{latestPayment.transactionRef}</span>
                      </div>
                    )}
                  </div>
                )}

                <div className="flex flex-wrap gap-3 mt-4">
                  {!hasSuccessfulPayment && order.status !== 'CANCELLED' && (
                    <button
                      onClick={handlePay}
                      disabled={paying}
                      className="inline-flex items-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors disabled:opacity-50"
                    >
                      <CreditCard className="w-4 h-4" />
                      {paying ? 'Recording...' : 'Pay now (mock)'}
                    </button>
                  )}
                  <button
                    onClick={handleInvoice}
                    disabled={invoiceLoading}
                    className="inline-flex items-center gap-2 px-4 py-2 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors disabled:opacity-50"
                  >
                    <FileText className="w-4 h-4" />
                    {invoiceLoading ? 'Loading...' : 'View invoice'}
                  </button>
                </div>
                {hasSuccessfulPayment && (
                  <p className="mt-2 text-sm text-green-700">This order has a successful (mock) payment.</p>
                )}
              </div>
            </div>

            {/* Order Items */}
            <div className="bg-white rounded-xl shadow-sm border p-6">
              <h3 className="font-semibold text-gray-900 mb-4 flex items-center gap-2">
                <ShoppingCart className="w-5 h-5 text-blue-600" />
                Order Items ({items.length})
              </h3>

              {items.length > 0 ? (
                <div className="overflow-x-auto">
                  <table className="w-full">
                    <thead>
                      <tr className="border-b border-gray-200">
                        <th className="text-left py-3 px-4 font-semibold text-gray-900">Product</th>
                        <th className="text-center py-3 px-4 font-semibold text-gray-900">ID</th>
                        <th className="text-center py-3 px-4 font-semibold text-gray-900">Quantity</th>
                        <th className="text-right py-3 px-4 font-semibold text-gray-900">Unit Price</th>
                        <th className="text-right py-3 px-4 font-semibold text-gray-900">Total</th>
                      </tr>
                    </thead>
                    <tbody>
                      {items.map((item, index) => {
                        const prod = item.product ?? {};
                        const price = parseFloat(item.priceAtPurchase ?? item.price ?? prod.price ?? 0);
                        const quantity = parseInt(item.quantity ?? item.qty ?? 1);
                        const total = price * quantity;

                        return (
                          <tr key={item.id ?? `item-${index}`} className="border-b border-gray-100 hover:bg-gray-50">
                            <td className="py-4 px-4">
                              <div>
                                <p className="font-medium text-gray-900">{prod.name ?? 'Product'}</p>
                                {prod.description && (
                                  <p className="text-sm text-gray-500">{prod.description}</p>
                                )}
                              </div>
                            </td>
                            <td className="py-4 px-4 text-center">
                              <span className="text-sm text-gray-600 font-mono">#{prod.id ?? 'N/A'}</span>
                            </td>
                            <td className="py-4 px-4 text-center">
                              <span className="font-medium">{quantity}</span>
                            </td>
                            <td className="py-4 px-4 text-right">
                              <span className="font-medium">₹{price.toFixed(2)}</span>
                            </td>
                            <td className="py-4 px-4 text-right">
                              <span className="font-semibold text-green-600">₹{total.toFixed(2)}</span>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>

                  {/* Order Totals */}
                  <div className="mt-6 pt-6 border-t border-gray-200">
                    <div className="max-w-md ml-auto space-y-2">
                      <div className="flex justify-between items-center">
                        <span className="text-gray-600">Subtotal:</span>
                        <span className="font-medium">₹{totals.subtotal.toFixed(2)}</span>
                      </div>
                      {Number(order.discountAmount) > 0 && (
                        <div className="flex justify-between items-center">
                          <span className="text-gray-600">
                            Discount{order.couponCode ? ` (${order.couponCode})` : ''}:
                          </span>
                          <span className="font-medium text-green-600">
                            -₹{Number(order.discountAmount).toFixed(2)}
                          </span>
                        </div>
                      )}
                      <div className="flex justify-between items-center pt-2 border-t border-gray-100">
                        <span className="text-lg font-semibold text-gray-900">Total:</span>
                        <span className="text-xl font-bold text-green-600">₹{order.totalAmount.toFixed(2)}</span>
                      </div>
                    </div>
                  </div>
                </div>
              ) : (
                <div className="text-center py-12">
                  <ShoppingCart className="w-12 h-12 text-gray-400 mx-auto mb-4" />
                  <p className="text-gray-600">No items in this order.</p>
                </div>
              )}
            </div>
          </div>

          {/* Sidebar - Actions & Status Management */}
          <div className="space-y-6">
            {/* Status Management */}
            <div className="bg-white rounded-xl shadow-sm border p-6">
              <h3 className="font-semibold text-gray-900 mb-4 flex items-center gap-2">
                <Clock className="w-5 h-5 text-blue-600" />
                Order Status
              </h3>
              
              <div className="space-y-4">
                <div className="flex items-center justify-between">
                  <label htmlFor="order-status" className="font-medium text-gray-700">
                    Current Status:
                  </label>
                  <span className={getStatusBadge(order.status)}>{order.status}</span>
                </div>

                <div className="space-y-3">
                  <label htmlFor="order-status" className="block font-medium text-gray-700">
                    Update Status:
                  </label>
                  <select
                    id="order-status"
                    value={status}
                    onChange={(e) => setStatus(e.target.value)}
                    className="w-full px-4 py-3 border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                  >
                    <option value="PENDING">Pending</option>
                    <option value="SHIPPED">Shipped</option>
                    <option value="DELIVERED">Delivered</option>
                    <option value="CANCELLED">Cancelled</option>
                  </select>
                  
                  <button 
                    onClick={handleSave} 
                    disabled={saving || status === order.status}
                    className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {saving ? (
                      <>
                        <RefreshCw className="w-4 h-4 animate-spin" />
                        Updating...
                      </>
                    ) : (
                      <>
                        <Save className="w-4 h-4" />
                        Update Status
                      </>
                    )}
                  </button>
                </div>
              </div>
            </div>

            {/* Quick Actions */}
            <div className="bg-white rounded-xl shadow-sm border p-6">
              <h3 className="font-semibold text-gray-900 mb-4 flex items-center gap-2">
                <Settings className="w-5 h-5 text-gray-600" />
                Quick Actions
              </h3>
              
              <div className="space-y-3">
                {/* Status-based quick actions */}
                {order.status === 'PENDING' && (
                  <button
                    onClick={() => handleQuickStatusChange('SHIPPED')}
                    disabled={saving}
                    className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-blue-500 text-white rounded-lg hover:bg-blue-600 transition-colors disabled:opacity-50"
                  >
                    <Truck className="w-4 h-4" />
                    Mark as Shipped
                  </button>
                )}

                {order.status === 'SHIPPED' && (
                  <button
                    onClick={() => handleQuickStatusChange('DELIVERED')}
                    disabled={saving}
                    className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-green-600 text-white rounded-lg hover:bg-green-700 transition-colors disabled:opacity-50"
                  >
                    <CheckCircle className="w-4 h-4" />
                    Mark as Delivered
                  </button>
                )}

                {(order.status === 'PENDING' || order.status === 'SHIPPED') && (
                  <button
                    onClick={() => {
                      if (window.confirm('Are you sure you want to cancel this order?')) {
                        handleQuickStatusChange('CANCELLED');
                      }
                    }}
                    disabled={saving}
                    className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-yellow-600 text-white rounded-lg hover:bg-yellow-700 transition-colors disabled:opacity-50"
                  >
                    <X className="w-4 h-4" />
                    Cancel Order
                  </button>
                )}

                {/* Communication Actions */}
                <div className="pt-3 border-t border-gray-100">
                  <button
                    onClick={() => {
                      const subject = `Order Update - ${order.id}`;
                      const body = `Dear ${order.customerName},\n\nYour order ${order.id} status has been updated to: ${order.status}\n\nThank you for your business!`;
                      window.location.href = `mailto:${order.customerEmail}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
                      setSuccessMessage('Email client opened');
                    }}
                    className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors"
                  >
                    <Mail className="w-4 h-4" />
                    Email Customer
                  </button>
                </div>

                {/* Danger Zone */}
                <div className="pt-3 border-t border-red-100">
                  <button
                    onClick={handleDelete}
                    disabled={saving}
                    className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors disabled:opacity-50"
                  >
                    <Trash2 className="w-4 h-4" />
                    Delete Order
                  </button>
                </div>
              </div>
            </div>

            {/* Order Timeline/History */}
            <div className="bg-white rounded-xl shadow-sm border p-6">
              <h3 className="font-semibold text-gray-900 mb-4 flex items-center gap-2">
                <Clock className="w-5 h-5 text-purple-600" />
                Order Timeline
              </h3>
              
              <div className="space-y-4">
                <div className="flex items-start gap-3">
                  <div className="w-3 h-3 bg-blue-600 rounded-full mt-2"></div>
                  <div>
                    <p className="font-medium text-gray-900">Order Created</p>
                    <p className="text-sm text-gray-600">
                      {order.orderDate ? new Date(order.orderDate).toLocaleString('en-GB') : 'Unknown'}
                    </p>
                  </div>
                </div>

                {order.status === 'SHIPPED' && (
                  <div className="flex items-start gap-3">
                    <div className="w-3 h-3 bg-blue-500 rounded-full mt-2"></div>
                    <div>
                      <p className="font-medium text-gray-900">Order Shipped</p>
                      <p className="text-sm text-gray-600">Currently in transit</p>
                    </div>
                  </div>
                )}

                {order.status === 'DELIVERED' && (
                  <>
                    <div className="flex items-start gap-3">
                      <div className="w-3 h-3 bg-blue-500 rounded-full mt-2"></div>
                      <div>
                        <p className="font-medium text-gray-900">Order Shipped</p>
                        <p className="text-sm text-gray-600">Package sent to customer</p>
                      </div>
                    </div>
                    <div className="flex items-start gap-3">
                      <div className="w-3 h-3 bg-green-600 rounded-full mt-2"></div>
                      <div>
                        <p className="font-medium text-gray-900">Order Delivered</p>
                        <p className="text-sm text-gray-600">Successfully completed</p>
                      </div>
                    </div>
                  </>
                )}

                {order.status === 'CANCELLED' && (
                  <div className="flex items-start gap-3">
                    <div className="w-3 h-3 bg-red-600 rounded-full mt-2"></div>
                    <div>
                      <p className="font-medium text-gray-900">Order Cancelled</p>
                      <p className="text-sm text-gray-600">Order was cancelled</p>
                    </div>
                  </div>
                )}
              </div>
            </div>

            {/* Order Summary Card */}
            <div className="bg-gradient-to-br from-blue-50 to-indigo-50 rounded-xl border border-blue-200 p-6">
              <h3 className="font-semibold text-gray-900 mb-4 flex items-center gap-2">
                <Package className="w-5 h-5 text-blue-600" />
                Order Summary
              </h3>
              
              <div className="space-y-3">
                <div className="flex justify-between items-center">
                  <span className="text-gray-600">Order ID:</span>
                  <span className="font-mono text-sm font-medium">#{order.id}</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-gray-600">Items:</span>
                  <span className="font-medium">{items.length}</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-gray-600">Status:</span>
                  <span className={`px-2 py-1 rounded-full text-xs font-medium ${
                    order.status === 'PENDING' ? 'bg-yellow-100 text-yellow-800' :
                    order.status === 'SHIPPED' ? 'bg-blue-100 text-blue-800' :
                    order.status === 'DELIVERED' ? 'bg-green-100 text-green-800' :
                    order.status === 'CANCELLED' ? 'bg-red-100 text-red-800' :
                    'bg-gray-100 text-gray-800'
                  }`}>
                    {order.status}
                  </span>
                </div>
                <div className="flex justify-between items-center pt-2 border-t border-blue-200">
                  <span className="font-semibold text-gray-900">Total:</span>
                  <span className="text-xl font-bold text-green-600">₹{order.totalAmount.toFixed(2)}</span>
                </div>
              </div>
            </div>

            {/* Additional Actions */}
            <div className="bg-white rounded-xl shadow-sm border p-6">
              <h3 className="font-semibold text-gray-900 mb-4 flex items-center gap-2">
                <Archive className="w-5 h-5 text-gray-600" />
                Additional Actions
              </h3>
              
              <div className="space-y-3">
                <button
                  onClick={() => {
                    navigator.clipboard.writeText(`Order #${order.id} - Status: ${order.status} - Total: ₹${order.totalAmount.toFixed(2)}`);
                    setSuccessMessage('Order details copied to clipboard');
                  }}
                  className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors"
                >
                  <FileText className="w-4 h-4" />
                  Copy Order Info
                </button>

                <button
                  onClick={() => {
                    const printContent = `
                      ORDER RECEIPT
                      
                      Order #: ${order.id}
                      Date: ${order.orderDate ? new Date(order.orderDate).toLocaleDateString() : 'N/A'}
                      Status: ${order.status}
                      
                      Customer: ${order.customerName || 'N/A'}
                      Email: ${order.customerEmail || 'N/A'}
                      
                      Shipping Address:
                      ${order.shippingAddress || 'N/A'}
                      
                      Items:
                      ${items.map(item => {
                        const prod = item.product || {};
                        const price = parseFloat(item.priceAtPurchase || item.price || 0);
                        const quantity = parseInt(item.quantity || 1);
                        return `- ${prod.name || 'Product'} x${quantity} @ ₹${price.toFixed(2)} = ₹${(price * quantity).toFixed(2)}`;
                      }).join('\n')}
                      
                      Total: ₹${order.totalAmount.toFixed(2)}
                    `;
                    
                    const printWindow = window.open('', '_blank');
                    printWindow.document.write(`
                      <html>
                        <head><title>Order ${order.id} Receipt</title></head>
                        <body style="font-family: monospace; white-space: pre-wrap; padding: 20px;">
                          ${printContent}
                        </body>
                      </html>
                    `);
                    printWindow.document.close();
                    printWindow.print();
                    setSuccessMessage('Receipt opened for printing');
                  }}
                  className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors"
                >
                  <Printer className="w-4 h-4" />
                  Print Receipt
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* Invoice modal — server-generated invoice for this order */}
        {invoice && (
          <div
            className="fixed inset-0 bg-black/50 flex items-center justify-center p-4 z-50"
            role="dialog"
            aria-modal="true"
            aria-labelledby="invoice-title"
          >
            <div className="bg-white rounded-xl shadow-2xl max-w-lg w-full p-6 max-h-[90vh] overflow-y-auto">
              <div className="flex items-start justify-between mb-4">
                <div>
                  <h2 id="invoice-title" className="text-xl font-bold text-gray-900">Invoice</h2>
                  <p className="font-mono text-sm text-gray-600">{invoice.invoiceNumber}</p>
                </div>
                <button
                  onClick={() => setInvoice(null)}
                  aria-label="Close invoice"
                  className="p-2 hover:bg-gray-100 rounded-lg"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              <div className="text-sm text-gray-700 space-y-1 mb-4">
                <p><span className="text-gray-500">Order:</span> #{invoice.orderId}</p>
                <p><span className="text-gray-500">Customer:</span> {invoice.customer}</p>
                <p><span className="text-gray-500">Email:</span> {invoice.customerEmail}</p>
                <p><span className="text-gray-500">Status:</span> {invoice.status}</p>
              </div>

              <table className="w-full text-sm mb-4">
                <thead>
                  <tr className="border-b border-gray-200">
                    <th className="text-left py-2">Product</th>
                    <th className="text-center py-2">Qty</th>
                    <th className="text-right py-2">Line total</th>
                  </tr>
                </thead>
                <tbody>
                  {(invoice.items || []).map((line, index) => (
                    <tr key={`${line.product}-${index}`} className="border-b border-gray-100">
                      <td className="py-2">{line.product}</td>
                      <td className="py-2 text-center">{line.quantity}</td>
                      <td className="py-2 text-right">₹{Number(line.lineTotal).toFixed(2)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <div className="space-y-1 text-sm">
                <div className="flex justify-between">
                  <span className="text-gray-600">Subtotal</span>
                  <span>₹{Number(invoice.subtotal).toFixed(2)}</span>
                </div>
                {Number(invoice.discount) > 0 && (
                  <div className="flex justify-between text-green-700">
                    <span>Discount{invoice.couponCode ? ` (${invoice.couponCode})` : ''}</span>
                    <span>-₹{Number(invoice.discount).toFixed(2)}</span>
                  </div>
                )}
                <div className="flex justify-between font-semibold text-gray-900 pt-2 border-t border-gray-200">
                  <span>Total ({invoice.currency})</span>
                  <span>₹{Number(invoice.total).toFixed(2)}</span>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Back to Orders Button */}
        <div className="mt-8 flex justify-center">
          <button 
            onClick={handleBack}
            className="flex items-center gap-2 px-6 py-3 bg-gray-200 text-gray-700 rounded-lg hover:bg-gray-300 transition-colors"
          >
            <ArrowLeft className="w-4 h-4" />
            Back to Orders List
          </button>
        </div>
      </div>
    </div>
  );
}