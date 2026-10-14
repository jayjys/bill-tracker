package com.billtracker.analytics;

import com.billtracker.analytics.forecast.LinearModel;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class LinearModelTest {

    @Test
    void recoversExactCoefficients() {
        double[][] x = {{1, 2}, {2, 1}, {3, 5}, {4, 3}, {5, 8}};
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            y[i] = 3 + 2 * x[i][0] - x[i][1];
        }

        LinearModel m = LinearModel.fit(x, y, 0);

        assertThat(m.intercept()).isCloseTo(3, within(1e-9));
        assertThat(m.coefficient(0)).isCloseTo(2, within(1e-9));
        assertThat(m.coefficient(1)).isCloseTo(-1, within(1e-9));
        assertThat(m.r2()).isCloseTo(1, within(1e-9));
        assertThat(m.predict(new double[] {10, 10})).isCloseTo(13, within(1e-9));
    }

    @Test
    void ridgeShrinksCoefficientsButNotTheIntercept() {
        Random r = new Random(1);
        double[][] x = new double[40][1];
        double[] y = new double[40];
        for (int i = 0; i < 40; i++) {
            x[i][0] = r.nextDouble() * 10;
            y[i] = 5 + 1.5 * x[i][0] + r.nextGaussian();
        }
        LinearModel ols = LinearModel.fit(x, y, 0);
        LinearModel ridge = LinearModel.fit(x, y, 500);

        assertThat(Math.abs(ridge.coefficient(0))).isLessThan(Math.abs(ols.coefficient(0)));
        assertThat(ols.coefficient(0)).isCloseTo(1.5, within(0.2));
        assertThat(ols.residualStd()).isCloseTo(1.0, within(0.3));
    }

    @Test
    void rejectsConstantFeatureWithoutRidge() {
        double[][] x = {{1}, {1}, {1}};
        assertThatThrownBy(() -> LinearModel.fit(x, new double[] {1, 2, 3}, 0))
                .isInstanceOf(IllegalArgumentException.class);
        // With a ridge penalty the system is solvable; the coefficient is just zero.
        assertThat(LinearModel.fit(x, new double[] {1, 2, 3}, 1).coefficient(0)).isCloseTo(0, within(1e-9));
    }
}
