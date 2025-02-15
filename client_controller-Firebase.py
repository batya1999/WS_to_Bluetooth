import time
import threading
import firebase_admin
from firebase_admin import credentials, db

try:
    from controller_reading.controllers import CalibratedController
    controller_available = True
except ImportError:
    print("Warning: CalibratedController module not found. Running without joystick input.")
    controller_available = False

def listen_to_joystick(ref):
    if not controller_available:
        print("Skipping joystick data retrieval because the controller module is unavailable.")
        return

    try:
        controller = CalibratedController()
    except Exception as e:
        print(f"Error initializing controller: {e}")
        return

    bound = None  # If bounds are always 1, skip extra computation

    while True:
        air_axes = controller.get_normalized_axes(new_range=(0, 200), bound=bound)
        buttons = controller.get_buttons()

        if air_axes and buttons:
            joystick_data = [
                int(air_axes["throttle"]),
                int(air_axes["yaw"]),
                int(air_axes["pitch"]),
                int(air_axes["roll"]),
                100,  # Fixed camera value
                0     # Fixed mode value
            ]

            ref.set({"readable": joystick_data})  # Direct write
            print(f"Sent to Firebase: {joystick_data}")

        # Uncomment if CPU usage is too high
        time.sleep(0.001)  # ~1000 updates per second

def main():
    cred = credentials.Certificate(r"C:\Users\Username\Desktop\Controller-Communication\Controller-Communication\communication\dji-v0-firebase-adminsdk-fbsvc-ac5461dcb3.json")
    firebase_admin.initialize_app(cred, {
        'databaseURL': 'https://dji-v0-default-rtdb.firebaseio.com'
    })

    ref = db.reference('/joystick_data')

    # Run joystick listener in a separate thread
    joystick_thread = threading.Thread(target=listen_to_joystick, args=(ref,), daemon=True)
    joystick_thread.start()

    # Keep main thread alive
    while True:
        time.sleep(0.01)

if __name__ == "__main__":
    main()
