import React, { useEffect, useState } from "react";
import {
  fetchAnalyticsSummary, fetchRfmSegments, fetchForecast,
  fetchDemandPrediction, fetchRiskOrders,
} from "../utils/api";
import {
  DollarSign, ShoppingCart, Users, Package, TrendingUp,
  AlertTriangle, Brain, LineChart, RefreshCw, ShieldAlert,
} from "lucide-react";

const inr = (n) => `₹${Number(n || 0).toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;

const StatusPill = ({ status }) => {
  const colors = {
    "Critical": "bg-red-100 text-red-800",
    "Reorder Recommended": "bg-orange-100 text-orange-800",
    "Low Stock": "bg-yellow-100 text-yellow-800",
    "Healthy Stock": "bg-emerald-100 text-emerald-800",
  };
  return (
    <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${colors[status] || "bg-gray-100 text-gray-700"}`}>
      {status}
    </span>
  );
};

const Insights = () => {
  const [summary, setSummary] = useState(null);
  const [rfm, setRfm] = useState(null);
  const [forecast, setForecast] = useState(null);
  const [demand, setDemand] = useState(null);
  const [risk, setRisk] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = async () => {
    setLoading(true);
    setError("");
    const results = await Promise.allSettled([
      fetchAnalyticsSummary(), fetchRfmSegments(), fetchForecast(7),
      fetchDemandPrediction(), fetchRiskOrders(),
    ]);
    if (results[0].status === "fulfilled") setSummary(results[0].value.data);
    if (results[1].status === "fulfilled") setRfm(results[1].value.data);
    if (results[2].status === "fulfilled") setForecast(results[2].value.data);
    if (results[3].status === "fulfilled") setDemand(results[3].value.data);
    if (results[4].status === "fulfilled") setRisk(results[4].value.data);
    if (results.every((r) => r.status === "rejected")) {
      setError("Failed to load analytics. Please check the backend connection.");
    }
    setLoading(false);
  };

  useEffect(() => { load(); }, []);

  if (loading) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
        {[...Array(8)].map((_, i) => (
          <div key={i} className="animate-pulse bg-white rounded-2xl h-28 shadow" />
        ))}
      </div>
    );
  }

  const kpis = summary ? [
    { label: "Total Revenue", value: inr(summary.totalRevenue), icon: DollarSign },
    { label: "Total Orders", value: summary.totalOrders, icon: ShoppingCart },
    { label: "Customers", value: summary.totalCustomers, icon: Users },
    { label: "Products", value: summary.totalProducts, icon: Package },
    { label: "Avg Order Value", value: inr(summary.averageOrderValue), icon: TrendingUp },
    { label: "Completed", value: summary.completedOrders, icon: Package },
    { label: "Cancelled", value: summary.cancelledOrders, icon: AlertTriangle },
  ] : [];

  return (
    <div className="space-y-8">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-800 flex items-center gap-2">
            <Brain className="w-6 h-6 text-indigo-600" /> Business Insights & ML
          </h1>
          <p className="text-gray-500 text-sm mt-1">
            All metrics are computed from live database records — no simulated numbers.
          </p>
        </div>
        <button onClick={load} className="flex items-center gap-2 px-4 py-2 bg-white border rounded-lg hover:bg-gray-50 text-sm">
          <RefreshCw className="w-4 h-4" /> Refresh
        </button>
      </div>

      {error && <div className="p-4 bg-red-50 border border-red-200 text-red-700 rounded-lg">{error}</div>}

      <div className="grid grid-cols-2 md:grid-cols-4 xl:grid-cols-7 gap-4">
        {kpis.map((k) => {
          const Icon = k.icon;
          return (
            <div key={k.label} className="bg-white rounded-2xl shadow p-4">
              <div className="inline-flex p-2 rounded-xl bg-gradient-to-r from-blue-500 to-indigo-600 mb-3">
                <Icon className="w-4 h-4 text-white" />
              </div>
              <div className="text-xl font-bold text-gray-800">{k.value}</div>
              <div className="text-xs text-gray-500">{k.label}</div>
            </div>
          );
        })}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="bg-white rounded-2xl shadow p-6">
          <h2 className="text-lg font-bold text-gray-800 flex items-center gap-2 mb-4">
            <LineChart className="w-5 h-5 text-blue-600" /> Sales Forecast (Linear Regression)
          </h2>
          {forecast?.status === "OK" ? (
            <>
              <div className="grid grid-cols-3 gap-3 mb-4 text-sm">
                <div className="bg-gray-50 rounded-lg p-3">
                  <div className="text-gray-500 text-xs">MAE</div>
                  <div className="font-semibold">{forecast.metrics.mae}</div>
                </div>
                <div className="bg-gray-50 rounded-lg p-3">
                  <div className="text-gray-500 text-xs">RMSE</div>
                  <div className="font-semibold">{forecast.metrics.rmse}</div>
                </div>
                <div className="bg-gray-50 rounded-lg p-3">
                  <div className="text-gray-500 text-xs">MAPE</div>
                  <div className="font-semibold">{forecast.metrics.mape === "N/A" ? "N/A" : `${forecast.metrics.mape}%`}</div>
                </div>
              </div>
              <div className="text-xs text-gray-500 mb-2">
                Trained on {forecast.metrics.trainSize} days, tested on {forecast.metrics.testSize} days.
                Daily trend: {inr(forecast.trendPerDay)}/day.
              </div>
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-gray-500 border-b">
                    <th className="py-1">Date</th>
                    <th className="py-1 text-right">Predicted Revenue</th>
                  </tr>
                </thead>
                <tbody>
                  {forecast.forecast.map((f) => (
                    <tr key={f.date} className="border-b last:border-0">
                      <td className="py-1">{f.date}</td>
                      <td className="py-1 text-right font-medium">{inr(f.predictedRevenue)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          ) : (
            <p className="text-sm text-gray-500">
              {forecast?.message || "Insufficient historical data for forecasting."}
            </p>
          )}
        </div>

        <div className="bg-white rounded-2xl shadow p-6">
          <h2 className="text-lg font-bold text-gray-800 flex items-center gap-2 mb-4">
            <Users className="w-5 h-5 text-purple-600" /> RFM Customer Segments
          </h2>
          {rfm && rfm.segments?.length > 0 ? (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-gray-500 border-b">
                  <th className="py-1">Segment</th>
                  <th className="py-1 text-right">Customers</th>
                  <th className="py-1 text-right">Revenue</th>
                  <th className="py-1 text-right">Avg Spend</th>
                </tr>
              </thead>
              <tbody>
                {rfm.segments.map((s) => (
                  <tr key={s.segment} className="border-b last:border-0">
                    <td className="py-1 font-medium">{s.segment}</td>
                    <td className="py-1 text-right">{s.customerCount}</td>
                    <td className="py-1 text-right">{inr(s.revenueContribution)}</td>
                    <td className="py-1 text-right">{inr(s.avgSpend)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="text-sm text-gray-500">No customer order history to segment yet.</p>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="bg-white rounded-2xl shadow p-6">
          <h2 className="text-lg font-bold text-gray-800 flex items-center gap-2 mb-4">
            <Package className="w-5 h-5 text-amber-600" /> Inventory Demand Prediction
          </h2>
          {demand?.products?.length > 0 ? (
            <div className="overflow-x-auto max-h-80 overflow-y-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-gray-500 border-b">
                    <th className="py-1">Product</th>
                    <th className="py-1 text-right">Stock</th>
                    <th className="py-1 text-right">Avg/Day</th>
                    <th className="py-1 text-right">Reorder At</th>
                    <th className="py-1">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {demand.products.map((p) => (
                    <tr key={p.productId} className="border-b last:border-0">
                      <td className="py-1">{p.name}</td>
                      <td className="py-1 text-right">{p.currentStock}</td>
                      <td className="py-1 text-right">{p.avgDailyDemand}</td>
                      <td className="py-1 text-right">{p.reorderThreshold}</td>
                      <td className="py-1"><StatusPill status={p.status} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-sm text-gray-500">No products to analyse.</p>
          )}
        </div>

        <div className="bg-white rounded-2xl shadow p-6">
          <h2 className="text-lg font-bold text-gray-800 flex items-center gap-2 mb-4">
            <ShieldAlert className="w-5 h-5 text-rose-600" /> Order Risk Review
          </h2>
          {risk ? (
            <>
              <div className="flex gap-3 mb-4 text-sm">
                <span className="px-3 py-1 bg-emerald-50 text-emerald-700 rounded-full">Normal: {risk.normalOrders}</span>
                <span className="px-3 py-1 bg-rose-50 text-rose-700 rounded-full">Review: {risk.reviewRequired}</span>
              </div>
              {risk.flaggedOrders?.length > 0 ? (
                <div className="space-y-3 max-h-72 overflow-y-auto">
                  {risk.flaggedOrders.map((o) => (
                    <div key={o.orderId} className="p-3 bg-rose-50 border border-rose-200 rounded-lg">
                      <div className="flex justify-between">
                        <span className="font-semibold text-gray-800">Order #{o.orderId}</span>
                        <span className="text-xs px-2 py-0.5 bg-rose-200 text-rose-900 rounded-full">{o.riskLevel}</span>
                      </div>
                      <div className="text-sm text-gray-600">{o.customer} · {inr(o.totalAmount)}</div>
                      <ul className="text-xs text-rose-700 mt-1 list-disc list-inside">
                        {o.reasons.map((r, i) => <li key={i}>{r}</li>)}
                      </ul>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-gray-500">No orders require review.</p>
              )}
              <p className="text-xs text-gray-400 mt-3">
                This is a review-triage signal, not a fraud determination.
              </p>
            </>
          ) : (
            <p className="text-sm text-gray-500">No order data to analyse.</p>
          )}
        </div>
      </div>
    </div>
  );
};

export default Insights;