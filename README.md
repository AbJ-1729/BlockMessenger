**BlockMessenger - Simple, Private, Offline messaging**

This project utilizes the concept of blockchain, and utilizes it for messaging. Each block is characterized by the message, sender address, and receiver address. The blocks are transmitted through Bluetooth Low Energy(BLE) and hence because of the size limit, are broken down and then reassembled at the receivers end. Since its based on BLE, its offline, and doesn't require wifi/data connectivity. 
User identification is done by a unique private key-public key pair, generated at registration. I utilized firebase backend as the only centralized server, just to ensure that each person gets a unique private key-public key pair, as collisions would lead to messages being delivered to more receipents than intended.
