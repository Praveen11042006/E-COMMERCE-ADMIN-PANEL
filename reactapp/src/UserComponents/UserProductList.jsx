import React, { useCallback, useEffect, useState } from "react";
import { fetchProductsPaged, fetchRecommendations } from "../utils/api.js";

const inr = (n) => `₹${Number(n || 0).toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;

const PAGE_SIZE = 12;

/**
 * Phase 13B — the customer catalogue now pages and filters on the server
 * (GET /api/products/paged) instead of loading every product into memory.
 */
const UserProductList = () => {
  const [products, setProducts] = useState([]);
  const [recommendations, setRecommendations] = useState([]);
  const [meta, setMeta] = useState({ page: 0, totalPages: 0, totalElements: 0, first: true, last: true });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("");
  const [minPrice, setMinPrice] = useState("");
  const [maxPrice, setMaxPrice] = useState("");
  const [applied, setApplied] = useState({ search: "", category: "", minPrice: "", maxPrice: "" });

  // Debounce text/number filters, then restart from the first page.
  // Re-applying an identical filter set must be a no-op: otherwise this effect
  // fires once on mount and creates a new `applied` object, which triggers a
  // duplicate fetch and can reset the page (back to 1) while the customer is
  // paginating within the debounce window.
  useEffect(() => {
    const next = {
      search: search.trim(),
      category: category.trim(),
      minPrice: minPrice.trim(),
      maxPrice: maxPrice.trim(),
    };
    if (
      next.search === applied.search &&
      next.category === applied.category &&
      next.minPrice === applied.minPrice &&
      next.maxPrice === applied.maxPrice
    ) {
      return undefined;
    }

    const timer = setTimeout(() => {
      setApplied(next);
      setPage(0);
    }, 350);
    return () => clearTimeout(timer);
  }, [search, category, minPrice, maxPrice, applied]);

  const loadProducts = useCallback(async () => {
    if (applied.minPrice && applied.maxPrice && Number(applied.minPrice) > Number(applied.maxPrice)) {
      setError("Minimum price cannot be greater than maximum price.");
      setProducts([]);
      setMeta({ page: 0, totalPages: 0, totalElements: 0, first: true, last: true });
      setLoading(false);
      return;
    }

    try {
      setLoading(true);
      setError(null);
      const res = await fetchProductsPaged({
        page,
        size: PAGE_SIZE,
        search: applied.search || undefined,
        category: applied.category || undefined,
        minPrice: applied.minPrice || undefined,
        maxPrice: applied.maxPrice || undefined,
      });
      const data = res.data || {};
      setProducts(Array.isArray(data.content) ? data.content : []);
      setMeta({
        page: data.page ?? page,
        totalPages: data.totalPages ?? 0,
        totalElements: data.totalElements ?? 0,
        first: data.first ?? true,
        last: data.last ?? true,
      });
    } catch (err) {
      console.error("loadProducts error", err);
      setError(err.response?.data?.message || "Failed to load products. Please try again later.");
      setProducts([]);
    } finally {
      setLoading(false);
    }
  }, [page, applied]);

  useEffect(() => {
    loadProducts();
  }, [loadProducts]);

  // Recommendations are independent of the catalogue page.
  useEffect(() => {
    fetchRecommendations()
      .then((res) => setRecommendations(res.data?.recommendations || []))
      .catch(() => setRecommendations([]));
  }, []);

  const clearFilters = () => {
    setSearch("");
    setCategory("");
    setMinPrice("");
    setMaxPrice("");
  };

  const hasFilters = Boolean(search || category || minPrice || maxPrice);

  if (loading && products.length === 0) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6">
        {[...Array(6)].map((_, i) => (
          <div key={i} className="animate-pulse bg-white p-4 rounded-lg shadow-md">
            <div className="h-48 bg-gray-200 rounded mb-4" />
            <div className="h-4 bg-gray-200 rounded w-3/4 mb-2" />
            <div className="h-4 bg-gray-200 rounded w-1/2" />
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="space-y-8">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <h2 className="text-3xl font-bold text-white">Explore Our Products</h2>
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search products..."
          aria-label="Search products"
          className="px-4 py-2 rounded-lg bg-[#2d2d44] text-white border border-gray-600 focus:outline-none focus:ring-2 focus:ring-blue-500 w-full sm:w-72"
        />
      </div>

      {/* Filters run on the server so only one page is ever loaded */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
        <input
          type="text"
          value={category}
          onChange={(e) => setCategory(e.target.value)}
          placeholder="Category"
          aria-label="Filter by category"
          className="px-4 py-2 rounded-lg bg-[#2d2d44] text-white border border-gray-600 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <input
          type="number"
          min="0"
          value={minPrice}
          onChange={(e) => setMinPrice(e.target.value)}
          placeholder="Min price"
          aria-label="Minimum price"
          className="px-4 py-2 rounded-lg bg-[#2d2d44] text-white border border-gray-600 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <input
          type="number"
          min="0"
          value={maxPrice}
          onChange={(e) => setMaxPrice(e.target.value)}
          placeholder="Max price"
          aria-label="Maximum price"
          className="px-4 py-2 rounded-lg bg-[#2d2d44] text-white border border-gray-600 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <button
          type="button"
          onClick={clearFilters}
          disabled={!hasFilters}
          className="px-4 py-2 rounded-lg bg-[#2d2d44] text-gray-200 border border-gray-600 hover:bg-[#3a3a55] transition-colors disabled:opacity-50"
        >
          Clear filters
        </button>
      </div>

      {error && <div className="text-center text-red-400 text-lg font-medium py-8" role="alert">{error}</div>}

      {recommendations.length > 0 && (
        <div>
          <h3 className="text-xl font-semibold text-white mb-4">Recommended for you</h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            {recommendations.slice(0, 4).map((r) => (
              <div key={r.productId} className="bg-[#1f1f2e] border border-[#2d2d44] rounded-lg overflow-hidden">
                {r.imageUrl ? (
                  <img src={r.imageUrl} alt={r.name} className="w-full h-32 object-cover"
                       onError={(e) => { e.target.style.display = "none"; }} />
                ) : (
                  <div className="w-full h-32 bg-[#2d2d44] flex items-center justify-center text-gray-500">No image</div>
                )}
                <div className="p-3">
                  <div className="font-semibold text-white truncate">{r.name}</div>
                  <div className="text-sm text-indigo-300 font-medium">{inr(r.price)}</div>
                  <div className="text-xs text-gray-400 mt-1">{r.reason}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {products.length === 0 ? (
        <div className="text-center text-gray-400 text-lg font-medium py-8">
          {hasFilters ? "No products match your filters." : "No products found."}
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6">
          {products.map((product) => (
            <div key={product.id} className="bg-[#1f1f2e] rounded-lg shadow-md overflow-hidden transition hover:shadow-xl border border-[#2d2d44]">
              {product.imageUrl ? (
                <img src={product.imageUrl} alt={product.name} className="w-full h-48 object-cover"
                     onError={(e) => { e.target.style.display = "none"; }} />
              ) : (
                <div className="w-full h-48 bg-[#2d2d44] flex items-center justify-center text-gray-500">No image</div>
              )}
              <div className="p-4">
                <h3 className="text-lg font-semibold text-white mb-1">{product.name}</h3>
                <p className="text-gray-400 mb-2 text-sm">{product.description || "No description available"}</p>
                <div className="flex justify-between items-center">
                  <p className="text-xl font-bold text-indigo-400">{inr(product.price)}</p>
                  <span className={`text-xs px-2 py-1 rounded-full ${
                    product.stockQuantity > 0 ? "bg-emerald-900/50 text-emerald-300" : "bg-red-900/50 text-red-300"
                  }`}>
                    {product.stockQuantity > 0 ? `${product.stockQuantity} in stock` : "Out of stock"}
                  </span>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Server-side pagination controls */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-4 border-t border-[#2d2d44] pt-6">
        <p className="text-sm text-gray-400">
          {meta.totalElements === 0
            ? "No products"
            : `Showing ${meta.page * PAGE_SIZE + 1}–${meta.page * PAGE_SIZE + products.length} of ${meta.totalElements}`}
        </p>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={meta.first || loading}
            className="px-4 py-2 rounded-lg bg-[#2d2d44] text-white border border-gray-600 hover:bg-[#3a3a55] disabled:opacity-50"
          >
            Previous
          </button>
          <span className="text-sm text-gray-300">
            Page {Math.min(meta.page + 1, Math.max(meta.totalPages, 1))} of {Math.max(meta.totalPages, 1)}
          </span>
          <button
            type="button"
            onClick={() => setPage((p) => p + 1)}
            disabled={meta.last || loading}
            className="px-4 py-2 rounded-lg bg-[#2d2d44] text-white border border-gray-600 hover:bg-[#3a3a55] disabled:opacity-50"
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
};

export default UserProductList;
