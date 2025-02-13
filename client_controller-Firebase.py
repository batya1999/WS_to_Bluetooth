import time
import firebase_admin
from firebase_admin import credentials
from firebase_admin import db

try:
    from controller_reading.controllers import CalibratedController
    controller_available = True
except ImportError:
    print("Warning: CalibratedController module not found. Running without joystick input.")
    controller_available = False

def same_axes(a, b, epsilon) -> bool:
    """Helper function to compare joystick axes"""
    if a is None and b is None:
        return True
    if a is None or b is None:
        return False
    return all(abs(a[k] - b[k]) <= epsilon for k in a)

def listen_to_joystick():
    if not controller_available:
        print("Skipping joystick data retrieval because the controller module is unavailable.")
        return

    try:
        controller = CalibratedController()
    except Exception as e:
        print(f"Error initializing controller: {e}")
        return

    bound = {
        "roll": 1,
        "pitch": 1,
        "yaw": 1,
        "throttle": 1,
        "camera": 1,
    }
    epsilon = 1
    last_axes = None
    last_btns = None

    # Reference to Firebase database
    ref = db.reference('/joystick_data')

    while True:
        air_axes = controller.get_normalized_axes(new_range=(0, 200), bound=bound)
        buttons = controller.get_buttons()

        if air_axes and buttons and (not same_axes(air_axes, last_axes, epsilon=epsilon) or last_btns != buttons):
            last_axes = air_axes
            last_btns = buttons

            # Ordered list instead of dictionary to maintain order in Firebase
            joystick_data = [
                int(air_axes["throttle"]),
                int(air_axes["yaw"]),
                int(air_axes["pitch"]),
                int(air_axes["roll"]),
                100,  # Fixed camera value
                0     # Fixed mode value
            ]

            # Send to Firebase
            ref.update({"readable": joystick_data})

            print(f"Sent to Firebase: {joystick_data}")

        time.sleep(0.05)

def main():
    cred = credentials.Certificate(r"C:\Users\Username\Desktop\Controller-Communication\Controller-Communication\communication\dji-v0-firebase-adminsdk-fbsvc-f02ea7c198.json")
    firebase_admin.initialize_app(cred, {
        'databaseURL': 'https://dji-v0-default-rtdb.firebaseio.com'
    })

    listen_to_joystick()

if __name__ == "__main__":
    main()
