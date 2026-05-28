import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import statsmodels.api as sm

# CSV file names
file1 = "5mmSiliconUnknown1IDmultiStrainRate_1_5_1 - 5mmSiliconUnknown1IDmultiStrainRate_1_5_1.csv"
file2 = "TestRig5mmSiliconUnknown1IDmultiDirection_1_1.csv"

# Read CSVs
df1 = pd.read_csv(file1, skiprows=15)
df2 = pd.read_csv(file2, skiprows=15)

# Extract numeric columns
x1 = pd.to_numeric(df1.iloc[:, 1], errors='coerce')
y1 = pd.to_numeric(df1.iloc[:, 2], errors='coerce')

x2 = pd.to_numeric(df2.iloc[:, 1], errors='coerce')
y2 = pd.to_numeric(df2.iloc[:, 2], errors='coerce')

# Clean NaNs
mask1 = ~(x1.isna() | y1.isna())
x1, y1 = x1[mask1], y1[mask1]

mask2 = ~(x2.isna() | y2.isna())
x2, y2 = x2[mask2], y2[mask2]

# -------------------------
# OLS regression function
# -------------------------
def run_ols(x, y):
    X = sm.add_constant(x)  # adds intercept
    model = sm.OLS(y, X).fit()
    return model

model1 = run_ols(x1, y1)
model2 = run_ols(x2, y2)

print("Dataset 1 regression summary:")
print(model1.summary())

print("\nDataset 2 regression summary:")
print(model2.summary())

# -------------------------
# Predictions
# -------------------------
y1_fit = model1.predict(sm.add_constant(x1))
y2_fit = model2.predict(sm.add_constant(x2))

# -------------------------
# Plot
# -------------------------
plt.figure(figsize=(10,6))

plt.scatter(x1, y1, s=10, label="Dataset 1")
plt.scatter(x2, y2, s=10, label="Dataset 2")

plt.plot(x1, y1_fit, label=f"OLS Fit 1 (R²={model1.rsquared:.3f})")
plt.plot(x2, y2_fit, label=f"OLS Fit 2 (R²={model2.rsquared:.3f})")

plt.xlabel("Time (s)")
plt.ylabel("Force (N)")
plt.title("OLS Regression on Two CSV Datasets")

plt.grid(True)
plt.legend()
plt.show()