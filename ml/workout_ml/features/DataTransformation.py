from scipy.signal import butter, filtfilt, lfilter


# Removes the high frequency data (that might be considered noise) from the data.
# PCA is not here: it is fitted inside each training fold (workout_ml.models.lopo.PCAFeatures).
class LowPassFilter:
    def low_pass_filter(
        self,
        data_table,
        col,
        sampling_frequency,
        cutoff_frequency,
        order=5,
        phase_shift=True,
    ):
        # http://stackoverflow.com/questions/12093594/how-to-implement-band-pass-butterworth-filter-with-scipy-signal-butter
        # Cutoff frequencies are expressed as the fraction of the Nyquist frequency, which is half the sampling frequency
        nyq = 0.5 * sampling_frequency
        cut = cutoff_frequency / nyq

        b, a = butter(order, cut, btype="low", output="ba", analog=False)
        if phase_shift:
            data_table[col + "_lowpass"] = filtfilt(b, a, data_table[col])
        else:
            data_table[col + "_lowpass"] = lfilter(b, a, data_table[col])
        return data_table
