import pandas as pd
df = pd.read_csv("Dataset.csv")
print("Event distribution in the training data:")
print(df['label'].value_counts())