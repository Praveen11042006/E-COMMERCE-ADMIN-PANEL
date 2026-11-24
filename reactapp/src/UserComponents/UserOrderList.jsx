import React, { useCallback, useEffect, useState } from "react";
import { fetchMyOrdersPaged } from "../utils/api.js";
import UserOrderItem from "./UserOrderItem";

const PAGE_SIZE = 10;

const UserOrderList = () => {
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  // Phase 10C: server-side pagination state.
  const [currentPage, setCurrentPage] = useState(1);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  const loadOrders = useCallback(async (page) => {
    setLoading(true);
    try {
      const res = await fetchMyOrdersPaged({ page: page - 1, size: PAGE_SIZE });
      const data = res.data || {};
      const content = Array.isArray(data.content) ? data.content : [];
      // The paged endpoint returns flattened items (productName); the item
      // component renders the nested shape, so it is rebuilt here.
      setOrders(content.map((o) => ({
        ...o,
        orderItems: Array.isArray(o.items)
          ? o.items.map((item, index) => ({
              ...item,
              id: item.id ?? index,
              quantity: item.quantity || 0,
              priceAtPurchase: parseFloat(item.priceAtPurchase) || 0,
              product: { name: item.productName || "Product" },
            }))
          : [],
      })));
      setTotalPages(data.totalPages ?? 0);
      setTotalElements(data.totalElements ?? 0);
      setError(null);
    } catch (err) {
      setError("Failed to load orders.");
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadOrders(currentPage);
  }, [loadOrders, currentPage]);

  // UserOrderItem already performed the cancel API call — only refresh local state
  // here. (Previously this handler called cancelMyOrder AGAIN, producing a
  // guaranteed "already cancelled" error alert right after a successful cancel.)
  const handleCancelSuccess = (id) => {
    setOrders(prev => prev.map(o => o.id === id ? { ...o, status: "CANCELLED" } : o));
  };

  if (loading && orders.length === 0) return <div className="p-4 text-gray-600">Loading orders...</div>;
  if (error) return <div className="p-4 text-red-500">{error}</div>;

  return (
    <div className="p-4">
      <h2 className="text-xl font-bold mb-4">My Orders</h2>
      {orders.length === 0 ? (
        <p className="text-gray-500">You have no orders yet.</p>
      ) : (
        <>
          <ul className="space-y-2">
            {orders.map(order => (
              <UserOrderItem key={order.id} order={order} onCancel={handleCancelSuccess} />
            ))}
          </ul>

          {/* Phase 10C: server-side pagination controls */}
          <div className="mt-4 flex items-center justify-between">
            <p className="text-sm text-gray-500">
              Page {currentPage} of {Math.max(totalPages, 1)} ({totalElements} orders)
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => setCurrentPage((p) => Math.max(p - 1, 1))}
                disabled={currentPage === 1 || loading}
                className="px-3 py-1.5 rounded border border-[#3b3b5c] text-sm text-gray-300 hover:text-white hover:border-blue-500 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                Previous
              </button>
              <button
                onClick={() => setCurrentPage((p) => Math.min(p + 1, Math.max(totalPages, 1)))}
                disabled={currentPage >= totalPages || loading}
                className="px-3 py-1.5 rounded border border-[#3b3b5c] text-sm text-gray-300 hover:text-white hover:border-blue-500 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                Next
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
};

export default UserOrderList;
