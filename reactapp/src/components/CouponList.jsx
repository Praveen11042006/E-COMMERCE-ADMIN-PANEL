import React, { useCallback, useEffect, useState } from 'react';
import { Ticket, Plus, Pencil, Trash2, AlertCircle, CheckCircle, RefreshCw, X } from 'lucide-react';
import { fetchCoupons, createCoupon, updateCoupon, deleteCoupon } from '../utils/api';

const emptyForm = {
  code: '',
  discountType: 'PERCENTAGE',
  discountValue: '',
  minOrderAmount: '',
  maxDiscount: '',
  usageLimit: '',
  expiryDate: '',
};

const formatCurrency = (amount) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(
    Number(amount) || 0
  );

const formatExpiry = (value) =>
  value ? new Date(value).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }) : 'No expiry';

/**
 * Phase 13B/13D — admin coupon management.
 * Lists, creates, edits and deletes coupons. The backend remains the authority:
 * this form only pre-checks obvious mistakes, and usage counters are never sent.
 */
const CouponList = () => {
  const [coupons, setCoupons] = useState([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});
  const [form, setForm] = useState(emptyForm);
  // null = create mode; otherwise the id of the coupon being edited.
  const [editingId, setEditingId] = useState(null);

  const loadCoupons = useCallback(async () => {
    try {
      setLoading(true);
      setError('');
      const res = await fetchCoupons();
      setCoupons(Array.isArray(res.data) ? res.data : []);
    } catch (err) {
      console.error('loadCoupons error', err);
      setError(err.response?.data?.message || 'Failed to load coupons. Please try again.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadCoupons();
  }, [loadCoupons]);

  useEffect(() => {
    if (!success) return undefined;
    const timer = setTimeout(() => setSuccess(''), 4000);
    return () => clearTimeout(timer);
  }, [success]);

  const setField = (name, value) => {
    setForm((prev) => ({ ...prev, [name]: value }));
    setFieldErrors((prev) => ({ ...prev, [name]: undefined }));
  };

  const validate = () => {
    const errors = {};
    if (!form.code.trim()) {
      errors.code = 'Coupon code is required';
    } else if (!/^[A-Za-z0-9_-]+$/.test(form.code.trim())) {
      errors.code = 'Use letters, digits, dashes or underscores only';
    }

    const value = Number(form.discountValue);
    if (!form.discountValue || Number.isNaN(value) || value <= 0) {
      errors.discountValue = 'Discount value must be greater than 0';
    } else if (form.discountType === 'PERCENTAGE' && value > 100) {
      errors.discountValue = 'Percentage discount cannot exceed 100';
    }

    if (form.minOrderAmount !== '' && Number(form.minOrderAmount) < 0) {
      errors.minOrderAmount = 'Minimum order amount cannot be negative';
    }
    if (form.discountType === 'PERCENTAGE' && form.maxDiscount !== '' && Number(form.maxDiscount) < 0) {
      errors.maxDiscount = 'Maximum discount cannot be negative';
    }
    if (form.usageLimit !== '' && Number(form.usageLimit) < 0) {
      errors.usageLimit = 'Usage limit cannot be negative';
    }
    if (form.expiryDate && new Date(form.expiryDate) <= new Date()) {
      errors.expiryDate = 'Expiry date must be in the future';
    }

    setFieldErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const resetForm = () => {
    setForm(emptyForm);
    setEditingId(null);
    setFieldErrors({});
  };

  // Load a coupon into the form. usedCount/active are shown in the table but are
  // never edited or submitted — the backend preserves them.
  const startEdit = (coupon) => {
    setError('');
    setSuccess('');
    setFieldErrors({});
    setEditingId(coupon.id);
    setForm({
      code: coupon.code || '',
      discountType: coupon.discountType || 'PERCENTAGE',
      discountValue: String(coupon.discountValue ?? ''),
      minOrderAmount: coupon.minOrderAmount ? String(coupon.minOrderAmount) : '',
      maxDiscount: coupon.maxDiscount ? String(coupon.maxDiscount) : '',
      usageLimit: coupon.usageLimit ? String(coupon.usageLimit) : '',
      expiryDate: coupon.expiryDate ? String(coupon.expiryDate).slice(0, 10) : '',
    });
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError('');
    setSuccess('');
    if (!validate()) return;

    const payload = {
      code: form.code.trim(),
      discountType: form.discountType,
      discountValue: Number(form.discountValue),
      minOrderAmount: form.minOrderAmount === '' ? 0 : Number(form.minOrderAmount),
      maxDiscount: form.discountType === 'PERCENTAGE' && form.maxDiscount !== '' ? Number(form.maxDiscount) : 0,
      usageLimit: form.usageLimit === '' ? 0 : Number(form.usageLimit),
    };
    // The backend field is a LocalDateTime; send the end of the chosen day.
    if (form.expiryDate) {
      payload.expiryDate = `${form.expiryDate}T23:59:59`;
    }

    try {
      setSaving(true);
      const res = editingId
        ? await updateCoupon(editingId, payload)
        : await createCoupon(payload);
      const savedCode = res.data?.code || payload.code.toUpperCase();
      setSuccess(editingId
        ? `Coupon ${savedCode} updated successfully`
        : `Coupon ${savedCode} created successfully`);
      resetForm();
      await loadCoupons();
    } catch (err) {
      console.error(editingId ? 'updateCoupon error' : 'createCoupon error', err);
      const data = err.response?.data;
      if (data?.errors) setFieldErrors(data.errors);
      setError(data?.message
        || (editingId ? 'Failed to update coupon. Please try again.' : 'Failed to create coupon. Please try again.'));
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (coupon) => {
    if (!window.confirm(`Delete coupon "${coupon.code}"?`)) return;
    try {
      setError('');
      await deleteCoupon(coupon.id);
      setSuccess(`Coupon ${coupon.code} deleted`);
      await loadCoupons();
    } catch (err) {
      console.error('deleteCoupon error', err);
      setError(err.response?.data?.message || 'Failed to delete coupon. Please try again.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
        <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4">
          <div className="flex items-center gap-3">
            <Ticket className="text-blue-600" size={24} />
            <div>
              <h1 className="text-2xl font-bold text-gray-900">Coupons</h1>
              <p className="text-gray-600">Create and retire discount codes used at checkout</p>
            </div>
          </div>
          <button
            type="button"
            onClick={loadCoupons}
            disabled={loading}
            className="inline-flex items-center gap-2 px-4 py-2 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors disabled:opacity-50"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            Refresh
          </button>
        </div>
      </div>

      {success && (
        <div className="flex items-center gap-3 bg-green-50 border border-green-200 text-green-800 rounded-lg p-4">
          <CheckCircle size={20} />
          <span className="text-sm font-medium">{success}</span>
        </div>
      )}
      {error && (
        <div className="flex items-center gap-3 bg-red-50 border border-red-200 text-red-800 rounded-lg p-4">
          <AlertCircle size={20} />
          <span className="text-sm font-medium">{error}</span>
        </div>
      )}

      {/* Create form */}
      <form onSubmit={handleSubmit} className="bg-white rounded-lg shadow-sm border border-gray-200 p-6 space-y-4">
        <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
          {editingId ? <Pencil size={18} className="text-blue-600" /> : <Plus size={18} className="text-blue-600" />}
          {editingId ? `Edit coupon ${form.code}` : 'New coupon'}
        </h2>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <div>
            <label htmlFor="coupon-code" className="block text-sm font-medium text-gray-700 mb-1">
              Code
            </label>
            <input
              id="coupon-code"
              value={form.code}
              onChange={(e) => setField('code', e.target.value)}
              placeholder="SAVE10"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
            />
            {fieldErrors.code && <p className="mt-1 text-xs text-red-600">{fieldErrors.code}</p>}
          </div>

          <div>
            <label htmlFor="coupon-type" className="block text-sm font-medium text-gray-700 mb-1">
              Discount type
            </label>
            <select
              id="coupon-type"
              value={form.discountType}
              onChange={(e) => setField('discountType', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
            >
              <option value="PERCENTAGE">Percentage (%)</option>
              <option value="FIXED">Fixed amount</option>
            </select>
          </div>

          <div>
            <label htmlFor="coupon-value" className="block text-sm font-medium text-gray-700 mb-1">
              Discount value
            </label>
            <input
              id="coupon-value"
              type="number"
              min="0"
              step="0.01"
              value={form.discountValue}
              onChange={(e) => setField('discountValue', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
            />
            {fieldErrors.discountValue && (
              <p className="mt-1 text-xs text-red-600">{fieldErrors.discountValue}</p>
            )}
          </div>

          <div>
            <label htmlFor="coupon-min-order" className="block text-sm font-medium text-gray-700 mb-1">
              Minimum order amount
            </label>
            <input
              id="coupon-min-order"
              type="number"
              min="0"
              step="0.01"
              value={form.minOrderAmount}
              onChange={(e) => setField('minOrderAmount', e.target.value)}
              placeholder="0"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
            />
            {fieldErrors.minOrderAmount && (
              <p className="mt-1 text-xs text-red-600">{fieldErrors.minOrderAmount}</p>
            )}
          </div>

          {form.discountType === 'PERCENTAGE' && (
            <div>
              <label htmlFor="coupon-max-discount" className="block text-sm font-medium text-gray-700 mb-1">
                Maximum discount (0 = no cap)
              </label>
              <input
                id="coupon-max-discount"
                type="number"
                min="0"
                step="0.01"
                value={form.maxDiscount}
                onChange={(e) => setField('maxDiscount', e.target.value)}
                placeholder="0"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
              />
              {fieldErrors.maxDiscount && (
                <p className="mt-1 text-xs text-red-600">{fieldErrors.maxDiscount}</p>
              )}
            </div>
          )}

          <div>
            <label htmlFor="coupon-usage-limit" className="block text-sm font-medium text-gray-700 mb-1">
              Usage limit (0 = unlimited)
            </label>
            <input
              id="coupon-usage-limit"
              type="number"
              min="0"
              value={form.usageLimit}
              onChange={(e) => setField('usageLimit', e.target.value)}
              placeholder="0"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
            />
            {fieldErrors.usageLimit && (
              <p className="mt-1 text-xs text-red-600">{fieldErrors.usageLimit}</p>
            )}
          </div>

          <div>
            <label htmlFor="coupon-expiry" className="block text-sm font-medium text-gray-700 mb-1">
              Expiry date (optional)
            </label>
            <input
              id="coupon-expiry"
              type="date"
              value={form.expiryDate}
              onChange={(e) => setField('expiryDate', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
            />
            {fieldErrors.expiryDate && (
              <p className="mt-1 text-xs text-red-600">{fieldErrors.expiryDate}</p>
            )}
          </div>
        </div>

        <div className="flex justify-end gap-2">
          {editingId && (
            <button
              type="button"
              onClick={resetForm}
              disabled={saving}
              className="inline-flex items-center gap-2 px-4 py-2 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors disabled:opacity-50"
            >
              <X size={16} />
              Cancel edit
            </button>
          )}
          <button
            type="submit"
            disabled={saving}
            className="inline-flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50"
          >
            {editingId ? <Pencil size={16} /> : <Plus size={16} />}
            {saving ? 'Saving...' : editingId ? 'Save changes' : 'Create coupon'}
          </button>
        </div>
      </form>

      {/* Coupon table */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-200">
          <h2 className="text-lg font-semibold text-gray-900">Coupons ({coupons.length})</h2>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-16">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
            <span className="sr-only">Loading coupons</span>
          </div>
        ) : coupons.length === 0 ? (
          <div className="text-center py-16 text-gray-600">No coupons yet.</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Code</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Discount</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Min order</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Usage</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Expiry</th>
                  <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</th>
                </tr>
              </thead>
              <tbody className="bg-white divide-y divide-gray-200">
                {coupons.map((coupon) => (
                  <tr key={coupon.id} className="hover:bg-gray-50">
                    <td className="px-6 py-4 whitespace-nowrap">
                      <span className="font-mono text-sm font-semibold text-gray-900">{coupon.code}</span>
                      {coupon.active === false && (
                        <span className="ml-2 text-xs px-2 py-0.5 rounded-full bg-gray-100 text-gray-600">inactive</span>
                      )}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                      {coupon.discountType === 'PERCENTAGE'
                        ? `${coupon.discountValue}%${coupon.maxDiscount > 0 ? ` (max ${formatCurrency(coupon.maxDiscount)})` : ''}`
                        : formatCurrency(coupon.discountValue)}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-700">
                      {formatCurrency(coupon.minOrderAmount)}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-700">
                      {coupon.usageLimit > 0 ? `${coupon.usedCount} / ${coupon.usageLimit}` : `${coupon.usedCount} / ∞`}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-700">
                      {formatExpiry(coupon.expiryDate)}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-right">
                      <div className="inline-flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => startEdit(coupon)}
                          aria-label={`Edit coupon ${coupon.code}`}
                          className="inline-flex items-center gap-1 px-3 py-2 text-xs font-medium text-blue-600 bg-blue-50 border border-blue-200 rounded-lg hover:bg-blue-100 transition-colors"
                        >
                          <Pencil size={14} />
                          Edit
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDelete(coupon)}
                          aria-label={`Delete coupon ${coupon.code}`}
                          className="inline-flex items-center gap-1 px-3 py-2 text-xs font-medium text-red-600 bg-red-50 border border-red-200 rounded-lg hover:bg-red-100 transition-colors"
                        >
                          <Trash2 size={14} />
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};

export default CouponList;
