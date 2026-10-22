import numpy as np
import pandas as pd


# Class to abstract a history of numerical values we can use as an attribute.
class NumericalAbstraction:
    # This function aggregates a list of values using the specified aggregation
    def aggregate_value(self, aggregation_function):
        # Compute the values and return the result.
        if aggregation_function == "mean":
            return np.mean
        elif aggregation_function == "max":
            return np.max
        elif aggregation_function == "min":
            return np.min
        elif aggregation_function == "median":
            return np.median
        elif aggregation_function == "std":
            return np.std
        else:
            return np.nan

    # Abstract numerical columns specified given a window size (i.e. the number of time points from the past considered) and an aggregation function.
    # The new columns are built together and concatenated once (no DataFrame fragmentation).
    # The window is trailing, so a row only sees itself and earlier rows. The caller is
    # responsible for passing a single set; the first window_size - 1 rows are NaN.
    def abstract_numerical(self, data_table, cols, window_size, aggregation_function):
        func = self.aggregate_value(aggregation_function)
        new = {
            col + "_temp_" + aggregation_function + "_ws_" + str(window_size): (
                data_table[col].rolling(window_size).apply(func, raw=True)
            )
            for col in cols
        }
        return pd.concat([data_table, pd.DataFrame(new, index=data_table.index)], axis=1)
