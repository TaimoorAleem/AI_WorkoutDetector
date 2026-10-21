import numpy as np

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
    def abstract_frequency(self, data_table, cols, window_size, sampling_rate):

        # Create new columns for the frequency data.
        freqs = np.round((np.fft.rfftfreq(int(window_size)) * sampling_rate), 3)

        n_rows = len(data_table.index)

        # Pre-compute all results into dictionaries of arrays (vectorized approach)
        results = {}
        for col in cols:
            results[col + "_max_freq"] = np.full(n_rows, np.nan)
            results[col + "_freq_weighted"] = np.full(n_rows, np.nan)
            results[col + "_pse"] = np.full(n_rows, np.nan)
            for freq in freqs:
                results[
                    col + "_freq_" + str(freq) + "_Hz_ws_" + str(window_size)
                ] = np.full(n_rows, np.nan)

        # Pass over the dataset (we cannot compute it when we do not have enough history)
        # and compute the values.
        for i in range(window_size, n_rows):
            for col in cols:
                real_ampl, imag_ampl = self.find_fft_transformation(
                    data_table[col].iloc[
                        i - window_size : min(i + 1, n_rows)
                    ],
                    sampling_rate,
                )
                # We only look at the real part in this implementation.
                for j in range(0, len(freqs)):
                    results[
                        col + "_freq_" + str(freqs[j]) + "_Hz_ws_" + str(window_size)
                    ][i] = real_ampl[j]
                # And select the dominant frequency. We only consider the positive frequencies for now.

                results[col + "_max_freq"][i] = freqs[
                    np.argmax(real_ampl[0 : len(real_ampl)])
                ]
                real_ampl_sum = np.sum(real_ampl)
                if real_ampl_sum != 0:
                    results[col + "_freq_weighted"][i] = float(
                        np.sum(freqs * real_ampl)
                    ) / real_ampl_sum
                else:
                    results[col + "_freq_weighted"][i] = 0.0
                PSD = np.divide(np.square(real_ampl), float(len(real_ampl)))
                PSD_sum = np.sum(PSD)
                if PSD_sum != 0:
                    PSD_pdf = np.divide(PSD, PSD_sum)
                    # Avoid log(0) by filtering out zeros
                    PSD_pdf_nonzero = PSD_pdf[PSD_pdf > 0]
                    results[col + "_pse"][i] = -np.sum(np.log(PSD_pdf_nonzero) * PSD_pdf_nonzero)
                else:
                    results[col + "_pse"][i] = 0.0

        # Assign all columns at once to avoid DataFrame fragmentation
        for col_name, values in results.items():
            data_table[col_name] = values

        return data_table