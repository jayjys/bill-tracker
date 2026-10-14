package com.billtracker.analytics.forecast;

import java.util.Arrays;

/**
 * Ordinary least squares with an optional ridge penalty, solved from the normal
 * equations. Features are centred first so the intercept is never penalised.
 * Small and dependency-free: the models here have one to three features and a few
 * dozen rows.
 */
public final class LinearModel {

    private final double intercept;
    private final double[] coefficients;
    private final double r2;
    private final double residualStd;
    private final int n;

    private LinearModel(double intercept, double[] coefficients, double r2, double residualStd, int n) {
        this.intercept = intercept;
        this.coefficients = coefficients;
        this.r2 = r2;
        this.residualStd = residualStd;
        this.n = n;
    }

    public static LinearModel fit(double[][] x, double[] y, double ridge) {
        int n = y.length;
        if (n == 0 || x.length != n) {
            throw new IllegalArgumentException("Need matching, non-empty x and y");
        }
        int p = x[0].length;
        double[] xMean = new double[p];
        double yMean = Arrays.stream(y).average().orElse(0);
        for (double[] row : x) {
            for (int j = 0; j < p; j++) {
                xMean[j] += row[j] / n;
            }
        }
        // A = Xc'Xc + λI,  b = Xc'yc
        double[][] a = new double[p][p];
        double[] b = new double[p];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < p; j++) {
                double xj = x[i][j] - xMean[j];
                b[j] += xj * (y[i] - yMean);
                for (int k = 0; k < p; k++) {
                    a[j][k] += xj * (x[i][k] - xMean[k]);
                }
            }
        }
        for (int j = 0; j < p; j++) {
            a[j][j] += ridge;
        }
        double[] beta = p == 0 ? new double[0] : solve(a, b);
        double intercept = yMean;
        for (int j = 0; j < p; j++) {
            intercept -= beta[j] * xMean[j];
        }

        double sse = 0;
        double sst = 0;
        for (int i = 0; i < n; i++) {
            double pred = intercept;
            for (int j = 0; j < p; j++) {
                pred += beta[j] * x[i][j];
            }
            sse += (y[i] - pred) * (y[i] - pred);
            sst += (y[i] - yMean) * (y[i] - yMean);
        }
        double r2 = sst == 0 ? 0 : 1 - sse / sst;
        double std = Math.sqrt(sse / Math.max(1, n - p - 1));
        return new LinearModel(intercept, beta, r2, std, n);
    }

    /** Gaussian elimination with partial pivoting. */
    static double[] solve(double[][] a, double[] b) {
        int p = b.length;
        double[][] m = new double[p][];
        for (int i = 0; i < p; i++) {
            m[i] = Arrays.copyOf(a[i], p + 1);
            m[i][p] = b[i];
        }
        for (int col = 0; col < p; col++) {
            int pivot = col;
            for (int r = col + 1; r < p; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(m[pivot][col]) < 1e-12) {
                throw new IllegalArgumentException("Singular system: features are constant or collinear");
            }
            double[] tmp = m[col];
            m[col] = m[pivot];
            m[pivot] = tmp;
            for (int r = 0; r < p; r++) {
                if (r != col) {
                    double f = m[r][col] / m[col][col];
                    for (int c = col; c <= p; c++) {
                        m[r][c] -= f * m[col][c];
                    }
                }
            }
        }
        double[] x = new double[p];
        for (int i = 0; i < p; i++) {
            x[i] = m[i][p] / m[i][i];
        }
        return x;
    }

    public double predict(double[] x) {
        double y = intercept;
        for (int j = 0; j < coefficients.length; j++) {
            y += coefficients[j] * x[j];
        }
        return y;
    }

    public double intercept() {
        return intercept;
    }

    public double coefficient(int j) {
        return coefficients[j];
    }

    public double r2() {
        return r2;
    }

    public double residualStd() {
        return residualStd;
    }

    public int n() {
        return n;
    }
}
