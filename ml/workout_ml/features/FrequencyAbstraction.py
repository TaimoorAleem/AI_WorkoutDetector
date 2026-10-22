import numpy as np
import pandas as pd


# This class performs a Fourier transformation on the data to find frequencies that occur often and filter noise.
class FourierTransformation:
    # Find the amplitudes of the different frequencies using a fast fourier transformation. Here,
    # the sampling rate expresses the number of samples per second (i.e. Frequency is Hertz of the dataset).
    def find_fft_transformation(self, data, sampling_rate):
        # Create the transformation, this includes the amplitudes of both the real
        # and imaginary part.
        transformation = np.fft.rfft(data, len(data))
        return transformation.real, transformation.imag

    # Get frequencies over a certain window.
    # Row i uses samples i - window_size .. i (window_size + 1 samples, trailing, so no look-ahead);
    # the first window_size rows are NaN. The caller must pass a single set.
    # Vectorised over rows with sliding windows; the definitions are those of the original loop.
    def abstract_frequency(self, data_table, cols, window_size, sampling_rate):
        # Frequency grid used for the column names and the weighted/dominant frequency.
        freqs = np.round((np.fft.rfftfreq(int(window_size)) * sampling_rate), 3)
        n_rows = len(data_table.index)
        n_freq = len(freqs)

        new = {}
        for col in cols:
            columns = {
                f"{col}_freq_{freq}_Hz_ws_{window_size}": np.full(n_rows, np.nan) for freq in freqs
            }
            max_freq = np.full(n_rows, np.nan)
            weighted = np.full(n_rows, np.nan)
            pse = np.full(n_rows, np.nan)

            x = data_table[col].to_numpy(dtype=float)
            if n_rows > window_size:
                windows = np.lib.stride_tricks.sliding_window_view(x, window_size + 1)
                # real part of the FFT of each window; shape (n_windows, window_size // 2 + 1)
                real = np.fft.rfft(windows, axis=1).real
                if real.shape[1] != n_freq:
                    raise ValueError("FFT length does not match the frequency grid")
                valid = slice(window_size, n_rows)
                for j, freq in enumerate(freqs):
                    columns[f"{col}_freq_{freq}_Hz_ws_{window_size}"][valid] = real[:, j]
                max_freq[valid] = freqs[np.argmax(real, axis=1)]

                total = real.sum(axis=1)
                safe = np.where(total != 0, total, 1.0)
                weighted[valid] = np.where(total != 0, (real * freqs).sum(axis=1) / safe, 0.0)

                psd = np.square(real) / float(n_freq)
                psd_sum = psd.sum(axis=1)
                pdf = psd / np.where(psd_sum != 0, psd_sum, 1.0)[:, None]
                with np.errstate(divide="ignore", invalid="ignore"):
                    plogp = np.where(pdf > 0, pdf * np.log(pdf), 0.0)
                pse[valid] = np.where(psd_sum != 0, -plogp.sum(axis=1), 0.0)

            new.update(columns)
            new[col + "_max_freq"] = max_freq
            new[col + "_freq_weighted"] = weighted
            new[col + "_pse"] = pse

        return pd.concat([data_table, pd.DataFrame(new, index=data_table.index)], axis=1)
