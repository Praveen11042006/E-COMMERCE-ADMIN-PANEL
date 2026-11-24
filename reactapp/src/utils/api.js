import axios from "axios";

// Backend base URL — configurable per environment through Vite env variables.
// NOTE: anything prefixed with VITE_ is bundled into the browser bundle, so only
// the public backend URL may ever live here (no secrets/keys/passwords).
// Development fallback keeps localhost working without any .env file.
// An explicitly empty value is honoured so a same-origin deployment (reverse
// proxy in front of the API) can be built without hardcoding a host.
const BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

const API = axios.create({
  baseURL: BASE_URL,
  headers: { "Content-Type": "application/json" },
});

// Attach JWT token to every request if present
API.interceptors.request.use((config) => {
  const token = localStorage.getItem("token");
  if (token) {
    config.headers["Authorization"] = `Bearer ${token}`;
  }
  return config;
});

// Handle 401 globally — clear session and redirect to login.
// Skip this for the login request itself so the user sees the form error
// instead of a full page reload.
API.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status;
    const url = error.config?.url || "";
    const isLoginRequest = url.includes("/auth/login");

    if (status === 401 && !isLoginRequest) {
      localStorage.removeItem("token");
      localStorage.removeItem("role");
      localStorage.removeItem("userId");
      localStorage.removeItem("userName");
      localStorage.removeItem("userEmail");
      if (window.location.pathname !== "/login") {
        window.location.href = "/login";
      }
    }
    return Promise.reject(error);
  }
);

/* ------------------- AUTH APIs ------------------- */

export const loginUser = async (email, password) => {
  const response = await API.post("/auth/login", { email, password });
  const { token, role, userId, name, email: userEmail } = response.data;
  localStorage.setItem("token", token);
  localStorage.setItem("role", role);
  localStorage.setItem("userId", userId);
  localStorage.setItem("userName", name || "");
  localStorage.setItem("userEmail", userEmail || "");
  return response.data;
};

export const registerUser = async (name, email, password) => {
  const response = await API.post("/auth/register", { name, email, password });
  return response.data;
};

// Current authenticated user's profile (any role) — identified from JWT on backend.
export const getCurrentUser = () => API.get("/auth/me");

/* ------------------- PRODUCT APIs ------------------- */
// GET /api/products is public; write operations require ADMIN (enforced by backend)
export const fetchProducts = (params) => API.get("/api/products", { params });
// Phase 13B: server-paginated catalogue ({content, page, size, totalElements, ...}).
export const fetchProductsPaged = (params) => API.get("/api/products/paged", { params });
// Whole-table inventory summary backing the paginated admin product list.
export const fetchProductStats = () => API.get("/api/products/stats");
export const getProduct = (id) => API.get(`/api/products/${id}`);
export const createProduct = (data) => API.post("/api/products", data);
export const updateProduct = (id, data) => API.put(`/api/products/${id}`, data);
export const deleteProduct = (id) => API.delete(`/api/products/${id}`);

/* ------------------- ORDER APIs ------------------- */
// Admin: all orders (full array — kept for the analytics dashboard)
export const fetchOrders = () => API.get("/api/orders");
// User: own orders only (JWT-scoped on backend)
export const fetchMyOrders = () => API.get("/api/orders/my");
export const fetchMyOrder = (id) => API.get(`/api/orders/my/${id}`);
export const getOrder = (id) => API.get(`/api/orders/${id}`);
export const getOrderById = (id) => API.get(`/api/orders/${id}`);
export const createOrder = (data) => API.post("/api/orders", data);
export const updateOrderStatus = (id, status) =>
  API.patch(`/api/orders/${id}/status`, { status });
export const cancelMyOrder = (id) => API.patch(`/api/orders/${id}/cancel`);
export const deleteOrder = (id) => API.delete(`/api/orders/${id}`);

/* --------- Phase 10C: server-side pagination / DTO list APIs --------- */
// Admin: one page of orders (server-side paging, sorting and filtering).
// `page` is 0-based, `size` is the page size, `sort` is "field,dir" (e.g. "orderDate,desc").
export const fetchOrdersPaged = (params) => API.get("/api/orders/paged", { params });
// User: one page of the caller's own orders (ownership is enforced by the JWT).
export const fetchMyOrdersPaged = (params) => API.get("/api/orders/my/paged", { params });
// Admin: aggregate order metrics over the whole table (not just the current page).
export const fetchOrderStats = () => API.get("/api/orders/stats");
// Admin: CSV export of ALL orders matching the current filters.
export const exportOrdersCsv = (params) =>
  API.get("/api/orders/export", { params, responseType: "blob" });

/* ------------------- USER APIs (Admin only) ------------------- */
export const createUser = (userData) => API.post("/api/users", userData);
export const getAllUsers = () => API.get("/api/users");
// Phase 10C: one page of users (server-side paging, sorting, search, role filter).
export const getUsersPaged = (params) => API.get("/api/users/paged", { params });
export const getUserById = (id) => API.get(`/api/users/${id}`);
export const updateUser = (id, userData) => API.put(`/api/users/${id}`, userData);
export const deleteUser = (id) => API.delete(`/api/users/${id}`);

/* ------------------- ANALYTICS / ML APIs (Admin) ------------------- */
export const fetchAnalyticsSummary = () => API.get("/api/analytics/summary");
// Phase 13D: database-side chart aggregations (no full catalogue download).
export const fetchCategorySales = (params) => API.get("/api/analytics/category-sales", { params });
export const fetchInventory = () => API.get("/api/analytics/inventory");
// Phase 13E: dashboard aggregates + the scalar time-series projection.
export const fetchOrderMetrics = (params) => API.get("/api/analytics/order-metrics", { params });
export const fetchTimeSeries = (params) => API.get("/api/analytics/time-series", { params });
export const fetchRfmSegments = () => API.get("/api/rfm/segments");
export const fetchRecommendations = () => API.get("/api/recommendations");
export const fetchForecast = (days = 7) => API.get("/api/forecast", { params: { days } });
export const fetchDemandPrediction = () => API.get("/api/demand");
export const fetchRiskOrders = () => API.get("/api/risk/orders");

/* ------------------- PAYMENT / INVOICE APIs ------------------- */
// Mock payment — the backend derives the amount from the order itself;
// the client only ever supplies the orderId (never a price).
export const payOrder = (orderId, method = "MOCK") =>
  API.post("/api/payments", { orderId, method });
// Invoice built server-side from stored order + payment data.
export const fetchInvoice = (orderId) => API.get(`/api/payments/invoice/${orderId}`);
// Payment records already stored for an order (ownership enforced by the backend).
export const fetchPaymentsForOrder = (orderId) => API.get(`/api/payments/order/${orderId}`);

/* ------------------- COUPON APIs ------------------- */
// Validate a coupon for a subtotal (any authenticated user).
export const validateCoupon = (code, subtotal) =>
  API.post("/api/coupons/validate", { code, subtotal });
// Admin coupon management.
export const fetchCoupons = () => API.get("/api/coupons");
export const createCoupon = (data) => API.post("/api/coupons", data);
export const updateCoupon = (id, data) => API.put(`/api/coupons/${id}`, data);
export const deleteCoupon = (id) => API.delete(`/api/coupons/${id}`);

export default API;