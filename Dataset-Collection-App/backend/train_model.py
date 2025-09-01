# file: train_model.py
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import joblib
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler, LabelEncoder
from sklearn.metrics import classification_report, confusion_matrix, ConfusionMatrixDisplay
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import LSTM, Dense, Dropout
from tensorflow.keras.utils import to_categorical
from tensorflow.keras.callbacks import EarlyStopping, ModelCheckpoint
from sklearn.utils import class_weight

# --- 1. CONFIGURATION & DATA LOADING ---
print("--- Step 1: Loading and Preprocessing Data ---")
CSV_FILE = "Dataset.csv" # Make sure your dataset file is named this
df = pd.read_csv(CSV_FILE)

# Ensure data is sorted by time for correct sequencing
df = df.sort_values(by='timestamp').reset_index(drop=True)
print(f"Loaded {len(df)} data points.")

# --- 2. FEATURE ENGINEERING ---
print("\n--- Step 2: Engineering Features ---")
# --- UPDATED: Using the full 12 features for better performance ---
# This list MUST exactly match the FEATURES list in realtime_ai_worker.py
FEATURES = [
    "accX", "accY", "accZ", "accMag",
    "gyroX", "gyroY", "gyroZ", "gyroMag",
    "rotVecX", "rotVecY", "rotVecZ", "rotVecW"
]

# Fill any potential missing values (e.g., from the start of the trip)
df[FEATURES] = df[FEATURES].ffill().bfill()
print(f"Features selected ({len(FEATURES)} total): {FEATURES}")

# --- 3. ENCODE LABELS ---
print("\n--- Step 3: Encoding Labels ---")
label_encoder = LabelEncoder()
df['label_encoded'] = label_encoder.fit_transform(df['label'])
NUM_CLASSES = len(label_encoder.classes_)
label_map = {i: label for i, label in enumerate(label_encoder.classes_)}

print("Label mapping (Number -> Event):")
for code, name in label_map.items():
    print(f"  {code}: {name}")
print(f"\nOriginal event distribution:\n{df['label'].value_counts()}")

# --- 4. CREATE SEQUENTIAL WINDOWS ---
print("\n--- Step 4: Creating Sequential Windows for LSTM ---")
WINDOW_SIZE = 40  # 4 seconds of data (40 samples at 10 Hz)
STEP_SIZE = 10    # Create a new window every 1 second

X_sequences = []
y_labels = []

# Group data by trip to prevent creating sequences that span two different trips
for trip_id, group in df.groupby('tripId'):
    group_features = group[FEATURES].values
    group_labels = group['label_encoded'].values
    
    for i in range(0, len(group) - WINDOW_SIZE, STEP_SIZE):
        X_sequences.append(group_features[i:i + WINDOW_SIZE])
        
        # The label for the window is the most frequent label in that window
        y_labels.append(pd.Series(group_labels[i:i + WINDOW_SIZE]).mode()[0])

X = np.array(X_sequences)
y = np.array(y_labels)

# Convert labels to one-hot encoding for the model's loss function
y_categorical = to_categorical(y, num_classes=NUM_CLASSES)

print(f"\nCreated {len(X)} sequences (windows).")
print(f"Shape of X (input data): {X.shape}") # Should be (num_sequences, 40, 12)
print(f"Shape of y (labels): {y_categorical.shape}")

# --- 5. SCALE FEATURE DATA ---
print("\n--- Step 5: Scaling Feature Data ---")
# Use StandardScaler as it is robust. The scaler will be saved and used in the live script.
scaler = StandardScaler()
# Reshape 3D data to 2D to fit the scaler, then reshape it back to 3D.
X_reshaped = X.reshape(-1, X.shape[-1])
X_scaled_reshaped = scaler.fit_transform(X_reshaped)
X_scaled = X_scaled_reshaped.reshape(X.shape)
print("Data scaled successfully.")

# --- 6. SPLIT INTO TRAINING AND TESTING SETS ---
print("\n--- Step 6: Splitting Data into Training and Testing Sets ---")
X_train, X_test, y_train, y_test = train_test_split(
    X_scaled, y_categorical,
    test_size=0.25,
    random_state=42,
    stratify=y # Ensures consistent event distribution in train/test sets
)
print(f"Training set size: {len(X_train)} windows")
print(f"Testing set size: {len(X_test)} windows")

# --- 7. HANDLE CLASS IMBALANCE ---
print("\n--- Step 7: Calculating Class Weights for Imbalanced Data ---")
# This helps the model pay more attention to rare events like "Accident"
class_weights = class_weight.compute_class_weight(
    'balanced',
    classes=np.unique(y),
    y=y
)
class_weight_dict = dict(enumerate(class_weights))
print("Calculated class weights to handle data imbalance.")
print(class_weight_dict)

# --- 8. BUILD THE LSTM MODEL ---
print("\n--- Step 8: Building the LSTM Model Architecture ---")
model = Sequential([
    LSTM(units=64, return_sequences=True, input_shape=(X_train.shape[1], X_train.shape[2])),
    Dropout(0.3),
    LSTM(units=32),
    Dropout(0.3),
    Dense(units=32, activation='relu'), # An intermediate dense layer can help learning
    Dense(units=NUM_CLASSES, activation='softmax') # The final output layer
])

model.compile(
    optimizer='adam', 
    loss='categorical_crossentropy',
    metrics=['accuracy']
)
model.summary()

# --- 9. TRAIN THE MODEL ---
print("\n--- Step 9: Training the Model ---")
# Callbacks for smarter training
# EarlyStopping will stop training if validation loss doesn't improve for 10 epochs.
early_stopping = EarlyStopping(monitor='val_loss', patience=10, restore_best_weights=True)
# ModelCheckpoint saves only the best version of the model.
model_checkpoint = ModelCheckpoint('best_lstm_model.h5', monitor='val_loss', save_best_only=True)

history = model.fit(
    X_train, y_train,
    epochs=100, # Set a high number; EarlyStopping will find the best one
    batch_size=64,
    validation_data=(X_test, y_test),
    callbacks=[early_stopping, model_checkpoint],
    class_weight=class_weight_dict, # Apply the calculated class weights
    verbose=1
)
print("\nModel training complete!")

# --- 10. EVALUATE THE MODEL ---
print("\n--- Step 10: Evaluating Model Performance ---")
# The best model is loaded automatically by the callbacks
y_pred_probs = model.predict(X_test)
y_pred = np.argmax(y_pred_probs, axis=1)
y_test_labels = np.argmax(y_test, axis=1)

# Get original string labels for a more readable report
y_pred_str = label_encoder.inverse_transform(y_pred)
y_test_str = label_encoder.inverse_transform(y_test_labels)

print("\nClassification Report:")
print(classification_report(y_test_str, y_pred_str))

print("\nConfusion Matrix:")
cm = confusion_matrix(y_test_str, y_pred_str, labels=label_encoder.classes_)
disp = ConfusionMatrixDisplay(confusion_matrix=cm, display_labels=label_encoder.classes_)
disp.plot(xticks_rotation='vertical')
plt.title("Confusion Matrix")
plt.show()

# --- 11. SAVE ARTIFACTS FOR DEPLOYMENT ---
print("\n--- Step 11: Saving Model and Preprocessors for Deployment ---")
# The best model is already saved as 'best_lstm_model.h5' by the ModelCheckpoint callback.
joblib.dump(scaler, 'feature_scaler.pkl')
joblib.dump(label_encoder, 'label_encoder.pkl')
print("\n✅ Training pipeline finished. Artifacts are ready for the real-time worker.")