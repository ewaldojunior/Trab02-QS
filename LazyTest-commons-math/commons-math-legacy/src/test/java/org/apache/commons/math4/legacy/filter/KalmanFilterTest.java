/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.math4.legacy.filter;

import org.apache.commons.rng.simple.RandomSource;
import org.apache.commons.rng.sampling.distribution.ZigguratNormalizedGaussianSampler;
import org.apache.commons.rng.sampling.distribution.GaussianSampler;
import org.apache.commons.rng.sampling.distribution.ContinuousSampler;
import org.apache.commons.numbers.core.Precision;
import org.apache.commons.math4.legacy.linear.Array2DRowRealMatrix;
import org.apache.commons.math4.legacy.linear.ArrayRealVector;
import org.apache.commons.math4.legacy.linear.MatrixDimensionMismatchException;
import org.apache.commons.math4.legacy.linear.MatrixUtils;
import org.apache.commons.math4.legacy.linear.RealMatrix;
import org.apache.commons.math4.legacy.linear.RealVector;
import org.apache.commons.math4.core.jdkmath.JdkMath;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link KalmanFilter}.
 *
 */
public class KalmanFilterTest {

    @Test(expected=MatrixDimensionMismatchException.class)
    public void testTransitionMeasurementMatrixMismatch() {

        // A and H matrix do not match in dimensions

        // A = [ 1 ]
        RealMatrix A = new Array2DRowRealMatrix(new double[] { 1d });
        // no control input
        RealMatrix B = null;
        // H = [ 1 1 ]
        RealMatrix H = new Array2DRowRealMatrix(new double[] { 1d, 1d });
        // Q = [ 0 ]
        RealMatrix Q = new Array2DRowRealMatrix(new double[] { 0 });
        // R = [ 0 ]
        RealMatrix R = new Array2DRowRealMatrix(new double[] { 0 });

        ProcessModel pm
            = new DefaultProcessModel(A, B, Q,
                                      new ArrayRealVector(new double[] { 0 }), null);
        MeasurementModel mm = new DefaultMeasurementModel(H, R);
        new KalmanFilter(pm, mm);
        Assert.fail("transition and measurement matrix should not be compatible");
    }

    @Test(expected=MatrixDimensionMismatchException.class)
    public void testTransitionControlMatrixMismatch() {

        // A and B matrix do not match in dimensions

        // A = [ 1 ]
        RealMatrix A = new Array2DRowRealMatrix(new double[] { 1d });
        // B = [ 1 1 ]
        RealMatrix B = new Array2DRowRealMatrix(new double[] { 1d, 1d });
        // H = [ 1 ]
        RealMatrix H = new Array2DRowRealMatrix(new double[] { 1d });
        // Q = [ 0 ]
        RealMatrix Q = new Array2DRowRealMatrix(new double[] { 0 });
        // R = [ 0 ]
        RealMatrix R = new Array2DRowRealMatrix(new double[] { 0 });

        ProcessModel pm
            = new DefaultProcessModel(A, B, Q,
                                      new ArrayRealVector(new double[] { 0 }), null);
        MeasurementModel mm = new DefaultMeasurementModel(H, R);
        new KalmanFilter(pm, mm);
        Assert.fail("transition and control matrix should not be compatible");
    }

    @Test
    public void testConstant() {
        // simulates a simple process with a constant state and no control input

        double constantValue = 10d;
        double measurementNoise = 0.1d;
        double processNoise = 1e-5d;

        // A = [ 1 ]
        RealMatrix A = new Array2DRowRealMatrix(new double[] { 1d });
        // no control input
        RealMatrix B = null;
        // H = [ 1 ]
        RealMatrix H = new Array2DRowRealMatrix(new double[] { 1d });
        // x = [ 10 ]
        RealVector x = new ArrayRealVector(new double[] { constantValue });
        // Q = [ 1e-5 ]
        RealMatrix Q = new Array2DRowRealMatrix(new double[] { processNoise });
        // R = [ 0.1 ]
        RealMatrix R = new Array2DRowRealMatrix(new double[] { measurementNoise });

        ProcessModel pm
            = new DefaultProcessModel(A, B, Q,
                                      new ArrayRealVector(new double[] { constantValue }), null);
        MeasurementModel mm = new DefaultMeasurementModel(H, R);
        KalmanFilter filter = new KalmanFilter(pm, mm);

        Assert.assertEquals(1, filter.getMeasurementDimension());
        Assert.assertEquals(1, filter.getStateDimension());

        assertMatrixEquals(Q.getData(), filter.getErrorCovariance());

        // check the initial state
        double[] expectedInitialState = new double[] { constantValue };
        assertVectorEquals(expectedInitialState, filter.getStateEstimation());

        RealVector pNoise = new ArrayRealVector(1);
        RealVector mNoise = new ArrayRealVector(1);

        final ContinuousSampler rand = createGaussianSampler(0, 1);

        // iterate 60 steps
        for (int i = 0; i < 60; i++) {
            filter.predict();

            // Simulate the process
            pNoise.setEntry(0, processNoise * rand.sample());

            // x = A * x + p_noise
            x = A.operate(x).add(pNoise);

            // Simulate the measurement
            mNoise.setEntry(0, measurementNoise * rand.sample());

            // z = H * x + m_noise
            RealVector z = H.operate(x).add(mNoise);

            filter.correct(z);

            // state estimate shouldn't be larger than measurement noise
            double diff = JdkMath.abs(constantValue - filter.getStateEstimation()[0]);
            // System.out.println(diff);
            Assert.assertTrue(Precision.compareTo(diff, measurementNoise, 1e-6) < 0);
        }

        // error covariance should be already very low (< 0.02)
        Assert.assertTrue(Precision.compareTo(filter.getErrorCovariance()[0][0],
                                              0.02d, 1e-6) < 0);

        //  **** union methods ****

        // simulates a vehicle, accelerating at a constant rate (0.1 m/s)

        // discrete time interval
        double dt_acceleration = 0.1d;
        // position measurement noise (meter)
        double measurementNoise_acceleration = 10d;
        // acceleration noise (meter/sec^2)
        double accelNoise_acceleration = 0.2d;

        // A = [ 1 dt ]
        //     [ 0  1 ]
        RealMatrix A_acceleration = new Array2DRowRealMatrix(new double[][] { { 1, dt_acceleration }, { 0, 1 } });

        // B = [ dt^2/2 ]
        //     [ dt     ]
        RealMatrix B_acceleration = new Array2DRowRealMatrix(
                new double[][] { { JdkMath.pow(dt_acceleration, 2d) / 2d }, { dt_acceleration } });

        // H = [ 1 0 ]
        RealMatrix H_acceleration = new Array2DRowRealMatrix(new double[][] { { 1d, 0d } });

        // x = [ 0 0 ]
        RealVector x_acceleration = new ArrayRealVector(new double[] { 0, 0 });

        RealMatrix tmp_acceleration = new Array2DRowRealMatrix(
                new double[][] { { JdkMath.pow(dt_acceleration, 4d) / 4d, JdkMath.pow(dt_acceleration, 3d) / 2d },
                        { JdkMath.pow(dt_acceleration, 3d) / 2d, JdkMath.pow(dt_acceleration, 2d) } });

        // Q = [ dt^4/4 dt^3/2 ]
        //     [ dt^3/2 dt^2   ]
        RealMatrix Q_acceleration = tmp_acceleration.scalarMultiply(JdkMath.pow(accelNoise_acceleration, 2));

        // P0 = [ 1 1 ]
        //      [ 1 1 ]
        RealMatrix P0_acceleration = new Array2DRowRealMatrix(new double[][] { { 1, 1 }, { 1, 1 } });

        // R = [ measurementNoise^2 ]
        RealMatrix R_acceleration = new Array2DRowRealMatrix(
                new double[] { JdkMath.pow(measurementNoise_acceleration, 2) });

        // constant control input, increase velocity by 0.1 m/s per cycle
        RealVector u_acceleration = new ArrayRealVector(new double[] { 0.1d });

        ProcessModel pm_acceleration = new DefaultProcessModel(A_acceleration, B_acceleration, Q_acceleration, x_acceleration, P0_acceleration);
        MeasurementModel mm_acceleration = new DefaultMeasurementModel(H_acceleration, R_acceleration);
        KalmanFilter filter_acceleration = new KalmanFilter(pm_acceleration, mm_acceleration);

        Assert.assertEquals(1, filter_acceleration.getMeasurementDimension());
        Assert.assertEquals(2, filter_acceleration.getStateDimension());

        assertMatrixEquals(P0_acceleration.getData(), filter_acceleration.getErrorCovariance());

        // check the initial state
        double[] expectedInitialState_acceleration = new double[] { 0.0, 0.0 };
        assertVectorEquals(expectedInitialState_acceleration, filter_acceleration.getStateEstimation());

        final ContinuousSampler rand_acceleration = createGaussianSampler(0, 1);

        RealVector tmpPNoise_acceleration = new ArrayRealVector(
                new double[] { JdkMath.pow(dt_acceleration, 2d) / 2d, dt_acceleration });

        // iterate 60 steps
        for (int i = 0; i < 60; i++) {
            filter_acceleration.predict(u_acceleration);

            // Simulate the process
            RealVector pNoise_acceleration = tmpPNoise_acceleration.mapMultiply(accelNoise_acceleration * rand_acceleration.sample());

            // x = A * x + B * u + pNoise
            x_acceleration = A_acceleration.operate(x_acceleration).add(B_acceleration.operate(u_acceleration)).add(pNoise_acceleration);

            // Simulate the measurement
            double mNoise_acceleration = measurementNoise_acceleration * rand_acceleration.sample();

            // z = H * x + m_noise
            RealVector z_acceleration = H_acceleration.operate(x_acceleration).mapAdd(mNoise_acceleration);

            filter_acceleration.correct(z_acceleration);

            // state estimate shouldn't be larger than the measurement noise
            double diff_acceleration = JdkMath.abs(x_acceleration.getEntry(0) - filter_acceleration.getStateEstimation()[0]);
            Assert.assertTrue(Precision.compareTo(diff_acceleration, measurementNoise_acceleration, 1e-6) < 0);
        }

        // error covariance of the velocity should be already very low (< 0.1)
        Assert.assertTrue(Precision.compareTo(filter_acceleration.getErrorCovariance()[1][1],
                0.1d, 1e-6) < 0);

    }
    
    /**
     * Represents an idealized Cannonball only taking into account gravity.
     */
    public static class Cannonball {

        private final double[] gravity = { 0, -9.81 };

        private final double[] velocity;
        private final double[] location;

        private double timeslice;

        public Cannonball(double timeslice, double angle, double initialVelocity) {
            this.timeslice = timeslice;

            final double angleInRadians = JdkMath.toRadians(angle);
            this.velocity = new double[] {
                    initialVelocity * JdkMath.cos(angleInRadians),
                    initialVelocity * JdkMath.sin(angleInRadians)
            };

            this.location = new double[] { 0, 0 };
        }

        public double getX() {
            return location[0];
        }

        public double getY() {
            return location[1];
        }

        public double getXVelocity() {
            return velocity[0];
        }

        public double getYVelocity() {
            return velocity[1];
        }

        public void step() {
            // break gravitational force into a smaller time slice.
            double[] slicedGravity = gravity.clone();
            for ( int i = 0; i < slicedGravity.length; i++ ) {
                slicedGravity[i] *= timeslice;
            }

            // apply the acceleration to velocity.
            double[] slicedVelocity = velocity.clone();
            for ( int i = 0; i < velocity.length; i++ ) {
                velocity[i] += slicedGravity[i];
                slicedVelocity[i] = velocity[i] * timeslice;
                location[i] += slicedVelocity[i];
            }

            // cannonballs shouldn't go into the ground.
            if ( location[1] < 0 ) {
                location[1] = 0;
            }
        }
    }

    @Test
    public void testCannonball() {
        // simulates the flight of a cannonball (only taking gravity and initial thrust into account)

        // number of iterations
        final int iterations = 144;
        // discrete time interval
        final double dt = 0.1d;
        // position measurement noise (meter)
        final double measurementNoise = 30d;
        // the initial velocity of the cannonball
        final double initialVelocity = 100;
        // shooting angle
        final double angle = 45;

        final Cannonball cannonball = new Cannonball(dt, angle, initialVelocity);

        final double speedX = cannonball.getXVelocity();
        final double speedY = cannonball.getYVelocity();

        // A = [ 1, dt, 0,  0 ]  =>  x(n+1) = x(n) + vx(n)
        //     [ 0,  1, 0,  0 ]  => vx(n+1) =        vx(n)
        //     [ 0,  0, 1, dt ]  =>  y(n+1) =              y(n) + vy(n)
        //     [ 0,  0, 0,  1 ]  => vy(n+1) =                     vy(n)
        final RealMatrix A = MatrixUtils.createRealMatrix(new double[][] {
                { 1, dt, 0,  0 },
                { 0,  1, 0,  0 },
                { 0,  0, 1, dt },
                { 0,  0, 0,  1 }
        });

        // The control vector, which adds acceleration to the kinematic equations.
        // 0          =>  x(n+1) =  x(n+1)
        // 0          => vx(n+1) = vx(n+1)
        // -9.81*dt^2 =>  y(n+1) =  y(n+1) - 1/2 * 9.81 * dt^2
        // -9.81*dt   => vy(n+1) = vy(n+1) - 9.81 * dt
        final RealVector controlVector =
                MatrixUtils.createRealVector(new double[] { 0, 0, 0.5 * -9.81 * dt * dt, -9.81 * dt } );

        // The control matrix B only expects y and vy, see control vector
        final RealMatrix B = MatrixUtils.createRealMatrix(new double[][] {
                { 0, 0, 0, 0 },
                { 0, 0, 0, 0 },
                { 0, 0, 1, 0 },
                { 0, 0, 0, 1 }
        });

        // We only observe the x/y position of the cannonball
        final RealMatrix H = MatrixUtils.createRealMatrix(new double[][] {
                { 1, 0, 0, 0 },
                { 0, 0, 0, 0 },
                { 0, 0, 1, 0 },
                { 0, 0, 0, 0 }
        });

        // our guess of the initial state.
        final RealVector initialState = MatrixUtils.createRealVector(new double[] { 0, speedX, 0, speedY } );

        // the initial error covariance matrix, the variance = noise^2
        final double var = measurementNoise * measurementNoise;
        final RealMatrix initialErrorCovariance = MatrixUtils.createRealMatrix(new double[][] {
                { var,    0,   0,    0 },
                {   0, 1e-3,   0,    0 },
                {   0,    0, var,    0 },
                {   0,    0,   0, 1e-3 }
        });

        // we assume no process noise -> zero matrix
        final RealMatrix Q = MatrixUtils.createRealMatrix(4, 4);

        // the measurement covariance matrix
        final RealMatrix R = MatrixUtils.createRealMatrix(new double[][] {
                { var,    0,   0,    0 },
                {   0, 1e-3,   0,    0 },
                {   0,    0, var,    0 },
                {   0,    0,   0, 1e-3 }
        });

        final ProcessModel pm = new DefaultProcessModel(A, B, Q, initialState, initialErrorCovariance);
        final MeasurementModel mm = new DefaultMeasurementModel(H, R);
        final KalmanFilter filter = new KalmanFilter(pm, mm);

        final ContinuousSampler rand = createGaussianSampler(0, measurementNoise);

        for (int i = 0; i < iterations; i++) {
            // get the "real" cannonball position
            double x = cannonball.getX();
            double y = cannonball.getY();

            // apply measurement noise to current cannonball position
            double nx = x + rand.sample();
            double ny = y + rand.sample();

            cannonball.step();

            filter.predict(controlVector);
            // correct the filter with our measurements
            filter.correct(new double[] { nx, 0, ny, 0 } );

            // state estimate shouldn't be larger than the measurement noise
            double diff = JdkMath.abs(cannonball.getY() - filter.getStateEstimation()[2]);
            Assert.assertTrue(Precision.compareTo(diff, measurementNoise, 1e-6) < 0);
        }

        // error covariance of the x/y-position should be already very low (< 3m std dev = 9 variance)

        Assert.assertTrue(Precision.compareTo(filter.getErrorCovariance()[0][0],
                                              9, 1e-6) < 0);

        Assert.assertTrue(Precision.compareTo(filter.getErrorCovariance()[2][2],
                                              9, 1e-6) < 0);
    }

    private void assertVectorEquals(double[] expected, double[] result) {
        Assert.assertEquals("Wrong number of rows.", expected.length,
                            result.length);
        for (int i = 0; i < expected.length; i++) {
            Assert.assertEquals("Wrong value at position [" + i + "]",
                                expected[i], result[i], 1.0e-6);
        }
    }

    private void assertMatrixEquals(double[][] expected, double[][] result) {
        Assert.assertEquals("Wrong number of rows.", expected.length,
                            result.length);
        for (int i = 0; i < expected.length; i++) {
            Assert.assertEquals("Wrong number of columns.", expected[i].length,
                                result[i].length);
            for (int j = 0; j < expected[i].length; j++) {
                Assert.assertEquals("Wrong value at position [" + i + "," + j
                                    + "]", expected[i][j], result[i][j], 1.0e-6);
            }
        }
    }

    /**
     * @param mu Mean
     * @param sigma Standard deviation.
     * @return a sampler that follows the N(mu,sigma) distribution.
     */
    private ContinuousSampler createGaussianSampler(double mu,
                                                    double sigma) {
        return GaussianSampler.of(ZigguratNormalizedGaussianSampler.of(RandomSource.JSF_64.create()),
                                  mu, sigma);
    }
}
