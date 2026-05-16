import numpy as np

def process_sensor_data(data_list):
    """
    Example function that uses numpy to process sensor data.
    """
    data = np.array(data_list)
    mean = np.mean(data)
    std = np.std(data)

    return {
        "mean": float(mean),
        "std": float(std),
        "status": "Healthy" if mean < 10 else "High Impact"
    }

def get_python_version():
    import sys
    return sys.version
