from pathlib import Path
import base64, zlib
TARGET = 'app/build.gradle.kts'
PAYLOAD = """eNqVVFtv2jAUfudXRDxRTbEIhLaz1JehVQJpZVorXicTH7IDjp3ZTsY09b/vOBfo2Co6PziOc76Lv5ykVFWO2kW/BhENlKNhZgomtLQGJRNlqTATHo0eXh0rjM3ZDvzGCkKyvfEKdQ+hsufBoLvpWLUowJUig+guaumn6Zh5yL5pIlfCOXReaD9sqqmgRAWPck/l09mg2ZSwFZXyc6O3mHe0YbwwuJBvog+jQN3ST9Ljnhc2B39U7bdrsI7I50YG9+n78wcPdLagm7CUzeLP8/Skoonr5LRxu8F7VJ6Q0bu7SJGt1XY0FLa4TuP6VnQRh/E8aOfmsqlQyXsQvrJAbyqqEX58QC1R5yTtbQV9ZZfdqgyBuBfqzlQ2gzk9pqw2qND/JOhS1GLdnoOtP355XKweviY3Z5H8B+g5vPu2HzrtzpD929GuLp4afubAj/7ZUrkVUgGTTrHlsXq5/kRyVyc9CSVoCTpDOLZxUSooQPumMSjhth0PLDMWeJjivT/whCUzNu4b+zUMtVjWRMBPq4TdvAoMHZgbk5Pz/jMqBL1zFIofFyQ9uSwtTVaF/S1FyP+4SdiYJZcNFGqPnns4+NhCZnKNoYQn1xfgtdAa9+ET4p2ZGAuRQ5xZU5ZgefrGAJCBzIFR64D1quD9Iu5oOfm4vRyE+x6AvL3Em0pTX0g+IRP9KTw4vziD7yo6L2/nlCVTNmn+Tr8BFg6RaA=="""
root = Path(__file__).resolve().parent
out = root / TARGET
out.parent.mkdir(parents=True, exist_ok=True)
out.write_bytes(zlib.decompress(base64.b64decode(PAYLOAD)))
print(f"PC4 wrote {TARGET} ({out.stat().st_size} bytes)")
