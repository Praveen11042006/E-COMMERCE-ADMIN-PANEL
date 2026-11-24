import React, { Suspense, lazy } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import Login from "./auth/Login.jsx";
import Register from "./auth/Register.jsx";
import PrivateRoute from "./components/PrivateRoute.jsx";

// Dashboards are code-split (React.lazy) so the initial bundle served to the
// login/register pages no longer pulls in Recharts, every admin/user page, and
// all ML/analytics components. Each dashboard chunk is fetched on first
// navigation; PrivateRoute still guards access before the chunk is shown.
const UserDashboard = lazy(() => import("./Dashboard/UserDashboard.jsx"));
const AdminDashboard = lazy(() => import("./Dashboard/AdminDashboard.jsx"));

const RouteFallback = () => (
  <div className="flex items-center justify-center min-h-screen bg-gray-100">
    <div
      className="w-10 h-10 border-4 border-indigo-500 border-t-transparent rounded-full animate-spin"
      role="status"
      aria-label="Loading"
    />
  </div>
);

const App = () => {
  return (
    <Suspense fallback={<RouteFallback />}>
      <Routes>
      {/* Auth */}
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />

      {/* Dashboards own their nested routes via internal <Routes>. */}
      <Route
        path="/user/*"
        element={
          <PrivateRoute role="USER">
            <UserDashboard />
          </PrivateRoute>
        }
      />
      <Route
        path="/admin/*"
        element={
          <PrivateRoute role="ADMIN">
            <AdminDashboard />
          </PrivateRoute>
        }
      />

      {/* Fallback */}
      <Route path="*" element={<Navigate to="/login" />} />
      </Routes>
    </Suspense>
  );
};

export default App;

