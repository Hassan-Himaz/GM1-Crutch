import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import statsmodels.api as sm

# CSV file names
file2 = "TestRig5mmSiliconUnknown1IDmultiDirection_1_1.csv"

# Read CSVs
df2 = pd.read_csv(file2, skiprows=8)

# Extract numeric columns

x2 = pd.to_numeric(df2.iloc[:, 1], errors='coerce')
y2 = pd.to_numeric(df2.iloc[:, 2], errors='coerce')

# Clean NaNs

mask2 = ~(x2.isna() | y2.isna())
x2, y2 = x2[mask2], y2[mask2]

# -------------------------
# OLS regression function
# -------------------------
def run_ols(x, y):
    X = sm.add_constant(x)  # adds intercept
    model = sm.OLS(y, X).fit()
    return model


def line_of_best_fit(model):
    """Return intercept, slope, and Force = m * Displacement + c from an OLS fit."""
    intercept = float(model.params.iloc[0])
    slope = float(model.params.iloc[1])
    sign = "+" if intercept >= 0 else "-"
    equation = f"Force = {slope:.4f} * Displacement {sign} {abs(intercept):.4f}"
    return intercept, slope, equation

# -------------------------
model2 = run_ols(x2, y2)

b2, m2, eq2 = line_of_best_fit(model2)


print("\nDataset 2 — line of best fit:")
print(f"  {eq2}")
print(f"  slope = {m2:.4f} N/mm,  intercept = {b2:.4f} N,  R² = {model2.rsquared:.4f}")


print("\nDataset 2 regression summary:")
print(model2.summary())

# -------------------------
# Predictions
# -------------------------
y2_fit = model2.predict(sm.add_constant(x2))

# -------------------------
# Plot
# -------------------------
plt.figure(figsize=(10,6))

plt.scatter(x2, y2, s=10, label="Dataset 2")

plt.plot(x2, y2_fit, label=f"Fit 2: y={m2:.1f}x{b2:+.1f} (R²={model2.rsquared:.3f})")

plt.xlabel("Displacement (mm)")
plt.ylabel("Force (N)")
plt.title("OLS Regression on Two CSV Datasets")

plt.grid(True)
plt.legend()
plt.show()