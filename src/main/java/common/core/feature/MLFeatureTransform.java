package common.core.feature;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.IntStream;

import common.core.linalg.FloatElement;
import common.core.linalg.MLDenseVector;
import common.core.linalg.MLSparseMatrix;
import common.core.linalg.MLSparseVector;
import com.google.common.util.concurrent.AtomicDoubleArray;

public abstract class MLFeatureTransform implements Serializable {

    public static class ColNormTransform extends MLFeatureTransform {

        private static final long serialVersionUID = -1777920290446866227L;
        private int normType;
        private MLDenseVector colNorm;

        public ColNormTransform(final int normTypeP) {
            this.normType = normTypeP;
        }

        @Override
        public void apply(final MLSparseFeature feature) {
            this.apply(feature.getFeatMatrixTransformed());
        }

        @Override
        public void apply(final MLSparseMatrix matrix) {
            this.colNorm = matrix.getColNorm(this.normType);
            this.applyInference(matrix);
        }

        @Override
        public String[] applyFeatureName(final String[] featureNames) {
            // do nothing
            return featureNames;
        }

        @Override
        public void applyInference(final MLSparseFeature feature) {
            this.applyInference(feature.getFeatMatrixTransformed());
        }

        @Override
        public void applyInference(final MLSparseMatrix matrix) {
            matrix.applyColNorm(this.colNorm);
        }

        @Override
        public void applyInference(final MLSparseVector vector) {
            if (vector == null || vector.isEmpty() == true) {
                return;
            }
            vector.applyNorm(this.colNorm);
        }
    }

    public static class ColSelectorTransform extends MLFeatureTransform {

        private static final long serialVersionUID = -4544414355983339509L;
        private Map<Integer, Integer> selectedColMap;
        private int nColsSelected;
        private int nnzCutOff;

        public ColSelectorTransform(final int nnzCutOffP) {
            this.nnzCutOff = nnzCutOffP;
        }

        @Override
        public void apply(final MLSparseFeature feature) {
            this.apply(feature.getFeatMatrixTransformed());
        }

        @Override
        public void apply(final MLSparseMatrix matrix) {
            // select columns that pass nnz cutoff
            this.selectedColMap = matrix.selectCols(this.nnzCutOff);

            // calculate new ncols
            this.nColsSelected = 0;
            for (Integer index : selectedColMap.values()) {
                if (this.nColsSelected < (index + 1)) {
                    this.nColsSelected = index + 1;
                }
            }

            // apply column selector to feature matrix
            this.applyInference(matrix);
        }

        @Override
        public String[] applyFeatureName(final String[] featureNames) {
            String[] selectedFeatNames = new String[this.selectedColMap.size()];
            for (int i = 0; i < featureNames.length; i++) {
                Integer newIndex = this.selectedColMap.get(i);
                if (newIndex != null) {
                    selectedFeatNames[newIndex] = featureNames[i];
                }
            }
            return selectedFeatNames;
        }

        @Override
        public void applyInference(final MLSparseFeature feature) {
            this.applyInference(feature.getFeatMatrixTransformed());
        }

        @Override
        public void applyInference(final MLSparseMatrix matrix) {
            matrix.applyColSelector(this.selectedColMap, this.nColsSelected);
        }

        @Override
        public void applyInference(final MLSparseVector vector) {
            vector.applyIndexSelector(this.selectedColMap, this.nColsSelected);
        }

    }

    public static class MinMaxTransform extends MLFeatureTransform {
        private static final long serialVersionUID = 1;

        private float[] min;
        private float[] max;

        public MinMaxTransform() {
        }


        @Override
        public void apply(final MLSparseFeature feature) {
            this.apply(feature.getFeatMatrixTransformed());
        }

        @Override
        public void apply(final MLSparseMatrix matrix) {
            //initialize min and max arrays
            AtomicDoubleArray minAtomic =
                    new AtomicDoubleArray(matrix.getNCols());
            AtomicDoubleArray maxAtomic =
                    new AtomicDoubleArray(matrix.getNCols());
            int cur = 0;
            while (cur < matrix.getNRows()) {
                MLSparseVector row = matrix.getRow(cur, false);
                if (row == null) {
                    cur++;
                    continue;
                }

                float[] values = row.toDense().getValues();
                for (int i = 0; i < values.length; i++) {
                    minAtomic.set(i, values[i]);
                    maxAtomic.set(i, values[i]);
                }
                break;
            }

            //compute min and max
            IntStream.range(0, matrix.getNRows()).parallel()
                    .forEach(rowIndex -> {
                        MLSparseVector row = matrix.getRow(rowIndex, false);
                        if (row == null) {
                            return;
                        }

                        float[] values = row.getValues();
                        int[] indexes = row.getIndexes();
                        for (int i = 0; i < indexes.length; i++) {
                            int index = indexes[i];
                            float value = values[i];

                            if (minAtomic.get(index) > value) {
                                minAtomic.set(index, value);
                            }
                            if (maxAtomic.get(index) < value) {
                                maxAtomic.set(index, value);
                            }
                        }
                    });
            this.min = new float[matrix.getNCols()];
            this.max = new float[matrix.getNCols()];
            for (int i = 0; i < this.min.length; i++) {
                this.min[i] = (float) minAtomic.get(i);
                this.max[i] = (float) maxAtomic.get(i);
            }

            // apply transform to this feature
            this.applyInference(matrix);
        }

        @Override
        public String[] applyFeatureName(final String[] featureNames) {
            // do nothing
            return featureNames;
        }

        @Override
        public void applyInference(final MLSparseFeature feature) {
            this.applyInference(feature.getFeatMatrixTransformed());
        }

        @Override
        public void applyInference(final MLSparseMatrix matrix) {
            IntStream.range(0, matrix.getNRows()).parallel()
                    .forEach(rowIndex -> {
                        MLSparseVector row = matrix.getRow(rowIndex, false);
                        if (row == null) {
                            return;
                        }
                        this.applyInference(row);
                        matrix.setRow(row, rowIndex);
                    });
        }

        @Override
        public void applyInference(final MLSparseVector vector) {
            if (vector == null || vector.isEmpty() == true) {
                return;
            }

            float[] values = vector.getValues();
            int[] indexes = vector.getIndexes();

            for (int i = 0; i < indexes.length; i++) {
                int index = indexes[i];
                float diff = this.max[index] - this.min[index];
                if (diff == 0f) {
                    values[i] = 1;
                } else {
                    values[i] =
                            (values[i] - this.min[index]) / diff;
                }
            }
        }
    }

    /**
     * Elementwise A^k transform.
     */
    public static class PowerTransform extends MLFeatureTransform {
        private static final long serialVersionUID = 1;

        private double power;

        public PowerTransform(final double powerP) {
            this.power = powerP;
        }

        @Override
        public void apply(final MLSparseFeature feature) {
            this.apply(feature.getFeatMatrixTransformed());
        }

        @Override
        public void apply(final MLSparseMatrix matrix) {
            this.applyInference(matrix);
        }

        @Override
        public String[] applyFeatureName(final String[] featureNames) {
            // do nothing
            return featureNames;
        }

        @Override
        public void applyInference(final MLSparseFeature feature) {
            this.applyInference(feature.getFeatMatrixTransformed());
        }

        @Override
        public void applyInference(final MLSparseMatrix matrix) {
            IntStream.range(0, matrix.getNRows()).parallel()
                    .forEach(rowIndex -> {
                        MLSparseVector row = matrix.getRow(rowIndex, false);
                        if (row == null) {
                            return;
                        }
                        this.applyInference(row);
                        matrix.setRow(row, rowIndex);
                    });
        }

        @Override
        public void applyInference(final MLSparseVector vector) {
            if (vector == null || vector.isEmpty() == true) {
                return;
            }
            float[] values = vector.getValues();
            for (int i = 0; i < values.length; i++) {
                values[i] = (float) Math.pow(values[i], this.power);
            }
        }
    }

    /**
     * Given a cutOff=p set all values greater than 1-p to percentile value
     * at 1-p. Similarly set all values smaller than p to percentile value at p.
     * For example, for cutOff=0.05 this will remove all "outliers"
     * above the 95'th percentile and below the 5'th percentile.
     */
    public static class RemoveOutlierTransform extends MLFeatureTransform {
        private static final long serialVersionUID = 1;

        private float cutOff;
        private float[] minCutOffs;
        private float[] maxCutOffs;

        public RemoveOutlierTransform(final float cutOffP) {
            this.cutOff = cutOffP;
        }

        @Override
        public void apply(final MLSparseFeature feature) {
            this.apply(feature.getFeatMatrixTransformed());
        }

        @Override
        public void apply(final MLSparseMatrix matrix) {
            //compute max and min cut-offs
            long[] colNNZ = matrix.getColNNZ();
            int[] cutOffRanks = new int[colNNZ.length];
            PriorityQueue<Float>[] minQueues = new PriorityQueue[colNNZ.length];
            PriorityQueue<Float>[] maxQueues = new PriorityQueue[colNNZ.length];

            for (int i = 0; i < colNNZ.length; i++) {
                cutOffRanks[i] = (int) Math.ceil(colNNZ[i] * this.cutOff) + 1;
                minQueues[i] = new PriorityQueue(cutOffRanks[i],
                        Collections.reverseOrder());
                maxQueues[i] = new PriorityQueue(cutOffRanks[i]);
            }

            IntStream.range(0, matrix.getNRows()).parallel()
                    .forEach(rowIndex -> {
                        MLSparseVector row = matrix.getRow(rowIndex, false);
                        if (row == null) {
                            return;
                        }

                        int[] indexes = row.getIndexes();
                        float[] values = row.getValues();
                        for (int i = 0; i < indexes.length; i++) {
                            int index = indexes[i];
                            float value = values[i];

                            synchronized (minQueues[index]) {
                                if (minQueues[index].size() < cutOffRanks[index]) {
                                    minQueues[index].add(value);

                                } else {
                                    if (minQueues[index].peek() > value) {
                                        minQueues[index].poll();
                                        minQueues[index].add(value);
                                    }
                                }
                            }

                            synchronized (maxQueues[index]) {
                                if (maxQueues[index].size() < cutOffRanks[index]) {
                                    maxQueues[index].add(value);

                                } else {
                                    if (maxQueues[index].peek() < value) {
                                        maxQueues[index].poll();
                                        maxQueues[index].add(value);
                                    }
                                }
                            }
                        }
                    });

            //set cut-off boundaries
            this.minCutOffs = new float[colNNZ.length];
            this.maxCutOffs = new float[colNNZ.length];
            for (int i = 0; i < cutOffRanks.length; i++) {
                if (minQueues[i].size() > 0) {
                    this.minCutOffs[i] = minQueues[i].peek();
                }
                if (maxQueues[i].size() > 0) {
                    this.maxCutOffs[i] = maxQueues[i].peek();
                }
            }

            this.applyInference(matrix);
        }

        @Override
        public String[] applyFeatureName(final String[] featureNames) {
            // do nothing
            return featureNames;
        }

        @Override
        public void applyInference(final MLSparseFeature feature) {
            this.applyInference(feature.getFeatMatrixTransformed());
        }

        @Override
        public void applyInference(final MLSparseMatrix matrix) {
            IntStream.range(0, matrix.getNRows()).parallel()
                    .forEach(rowIndex -> {
                        MLSparseVector row = matrix.getRow(rowIndex, false);
                        if (row == null) {
                            return;
                        }
                        this.applyInference(row);
                        matrix.setRow(row, rowIndex);
                    });
        }

        @Override
        public void applyInference(final MLSparseVector vector) {
            if (vector == null || vector.isEmpty() == true) {
                return;
            }
            int[] indexes = vector.getIndexes();
            float[] values = vector.getValues();
            for (int i = 0; i < values.length; i++) {
                if (values[i] > this.maxCutOffs[indexes[i]]) {
                    values[i] = this.maxCutOffs[indexes[i]];

                } else if (values[i] < this.minCutOffs[indexes[i]]) {
                    values[i] = this.minCutOffs[indexes[i]];
                }
            }
        }

        public float[] getMinCutOffs() {
            return this.minCutOffs;
        }

        public float[] getMaxCutOffs() {
            return this.maxCutOffs;
        }
    }

    public static class RowNormTransform extends MLFeatureTransform {

        private static final long serialVersionUID = -1777920290446866227L;
        private int normType;

        public RowNormTransform(final int normTypeP) {
            this.normType = normTypeP;
        }

        @Override
        public void apply(final MLSparseFeature feature) {
            this.apply(feature.getFeatMatrixTransformed());
        }

        @Override
        public void apply(final MLSparseMatrix matrix) {
            this.applyInference(matrix);
        }

        @Override
        public String[] applyFeatureName(final String[] featureNames) {
            // do nothing
            return featureNames;
        }

        @Override
        public void applyInference(final MLSparseFeature feature) {
            this.applyInference(feature.getFeatMatrixTransformed());
        }

        @Override
        public void applyInference(final MLSparseMatrix matrix) {
            MLDenseVector norm = matrix.getRowNorm(this.normType);
            matrix.applyRowNorm(norm);
        }

        @Override
        public void applyInference(final MLSparseVector vector) {
            if (vector == null || vector.isEmpty() == true) {
                return;
            }
            vector.applyNorm(this.normType);
        }
    }

    public static class StandardizeTransform extends MLFeatureTransform {

        private static final long serialVersionUID = -2289862537575019481L;
        private float[] mean;
        private float[] std;
        private float cutOff;

        public StandardizeTransform(final float cutOffP) {
            this.cutOff = cutOffP;
        }

        @Override
        public void apply(final MLSparseFeature feature) {
            this.apply(feature.getFeatMatrixTransformed());
        }

        @Override
        public void apply(final MLSparseMatrix matrix) {
            // compute mean
            this.mean = matrix.getColSum().getValues();
            long[] colNNZ = matrix.getColNNZ();
            for (int i = 0; i < this.mean.length; i++) {
                if (colNNZ[i] > 1) {
                    this.mean[i] /= colNNZ[i];
                }
            }

            // compute std
            this.std = new float[this.mean.length];
            IntStream.range(0, matrix.getNRows()).parallel()
                    .forEach(rowIndex -> {
                        MLSparseVector row = matrix.getRow(rowIndex, false);
                        if (row == null) {
                            return;
                        }
                        int[] indexes = row.getIndexes();
                        float[] values = row.getValues();
                        for (int i = 0; i < indexes.length; i++) {
                            float diff = values[i] - this.mean[indexes[i]];
                            synchronized (this.std) {
                                this.std[indexes[i]] += diff * diff;
                            }
                        }

                    });
            for (int i = 0; i < this.std.length; i++) {
                if (colNNZ[i] > 0) {
                    this.std[i] = (float) Math.sqrt(this.std[i] / colNNZ[i]);
                }
            }

            // apply transform to this feature
            this.applyInference(matrix);
        }

        @Override
        public String[] applyFeatureName(final String[] featureNames) {
            // do nothing
            return featureNames;
        }

        @Override
        public void applyInference(final MLSparseFeature feature) {
            this.applyInference(feature.getFeatMatrixTransformed());
        }

        @Override
        public void applyInference(final MLSparseMatrix matrix) {
            IntStream.range(0, matrix.getNRows()).parallel()
                    .forEach(rowIndex -> {
                        MLSparseVector row = matrix.getRow(rowIndex, false);
                        if (row == null) {
                            return;
                        }
                        this.applyInference(row);
                        matrix.setRow(row, rowIndex);
                    });
        }

        @Override
        public void applyInference(final MLSparseVector vector) {
            if (vector == null || vector.isEmpty() == true) {
                return;
            }

            int[] indexes = vector.getIndexes();
            float[] values = vector.getValues();
            int nnz = 0;
            for (int i = 0; i < indexes.length; i++) {
                int index = indexes[i];
                if (Math.abs(values[i] - this.mean[index]) < 1e-5) {
                    values[i] = 0;
                    continue;
                }
                nnz++;

                values[i] = (values[i] - this.mean[index]) / this.std[index];

                // clip standardized values
                if (values[i] > this.cutOff) {
                    values[i] = this.cutOff;

                } else if (values[i] < -this.cutOff) {
                    values[i] = -this.cutOff;
                }
            }
            if (nnz != values.length) {
                // remove zeros
                int[] newIndexes = new int[nnz];
                float[] newValues = new float[nnz];
                int cur = 0;
                for (int i = 0; i < indexes.length; i++) {
                    if (values[i] != 0) {
                        newIndexes[cur] = indexes[i];
                        newValues[cur] = values[i];
                        cur++;
                    }
                }
                vector.setIndexes(newIndexes);
                vector.setValues(newValues);
            }
        }

    }

    private static final long serialVersionUID = 3186575390529411219L;

    public abstract void apply(final MLSparseFeature feature);

    public abstract void apply(final MLSparseMatrix matrix);

    public abstract String[] applyFeatureName(final String[] featureNames);

    public abstract void applyInference(final MLSparseFeature feature);

    public abstract void applyInference(final MLSparseMatrix matrix);

    public abstract void applyInference(final MLSparseVector vector);
}
