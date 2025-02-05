import time
import firebase_admin
from firebase_admin import credentials
from firebase_admin import db
from controller_reading.controllers import CalibratedController

def same_axes(a, b, epsilon) -> bool:
    """Helper function to compare joystick axes"""
    if a is None and b is None:
        return True
    if a is None or b is None:
        return False
    return all(abs(a[k] - b[k]) <= epsilon for k in a)


def listen_to_joystick():
    try:
        controller = CalibratedController()
    except Exception as e:
        print(f"Error while looking for controller: {e}")
        return

    bound = {
        "roll": 0.2,
        "pitch": 0.2,
        "yaw": 0.2,
        "throttle": 0.2,
        "camera": 0.1,
    }
    epsilon = 1
    last_axes = None
    last_btns = None

    # Reference to Firebase database where joystick data will be stored
    ref = db.reference('/joystick_data')  # Firebase reference path

    while True:
        # Get the current state of the joystick axes and buttons
        air_axes = controller.get_normalized_axes(new_range=(0, 255), bound=bound)
        buttons = controller.get_buttons()

        # Check if there is any change in joystick state
        if air_axes and buttons and (not same_axes(air_axes, last_axes, epsilon=epsilon) or last_btns != buttons):
            last_axes = air_axes
            last_btns = buttons

            # Prepare the joystick data to send to Firebase
            joystick_data = {
                'axes': air_axes,
                'buttons': buttons,
                'takeoff': controller.get_takeoff(buttons),
                'land': controller.get_land(buttons),
            }

            # Send the data to Firebase Realtime Database
            ref.set(joystick_data)  # Set new data in Firebase
            print(f"Sent to Firebase: {joystick_data}")  # Optional: Log the data sent

        # Sleep for 50 milliseconds to avoid excessive updates
        time.sleep(0.05)


def main():
    # Path to Firebase service account key JSON file
    cred = credentials.Certificate("C:/Users/Username/Desktop/Controller-Communication/Controller-Communication/communication/dji-v0-firebase-adminsdk-fbsvc-aec497feec.json")

    
    # Initialize Firebase Admin SDK
    firebase_admin.initialize_app(cred, {
        'databaseURL': 'https://dji-v0-default-rtdb.firebaseio.com'  # Your Firebase Realtime Database URL
    })

    # Start listening to joystick data
    listen_to_joystick()


if __name__ == "__main__":
    main()
