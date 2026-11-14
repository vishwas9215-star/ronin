// Who sent an SMS: the sender code (the middle of "VM-HDFCBK-S") -> the company's name and colour, so the
// message list reads like the caller-ID apps (HDFC Bank in blue, Kotak in red...). Data only: add a line
// to add a company. Each has its colour + short letters; with the company's own website (last item)
// Ronin shows that site's own icon (like a browser tab does), fetched once from that site and kept.
// Codes are matched in full; "prefix" entries only colour other codes of the same group (e.g. "SBICRD" =
// SBI Card, a different company): they show their own code, no website icon, and their own row.
window.RONIN_SENDERS = {
  exact: {
    SBIINB: ['State Bank of India', '#22409a', 'SBI', 'sbi.co.in'], SBIOTP: ['State Bank of India', '#22409a', 'SBI', 'sbi.co.in'], SBIBNK: ['State Bank of India', '#22409a', 'SBI', 'sbi.co.in'], SBIPSG: ['State Bank of India', '#22409a', 'SBI', 'sbi.co.in'], SBYONO: ['State Bank of India', '#22409a', 'SBI', 'sbi.co.in'], CBSSBI: ['State Bank of India', '#22409a', 'SBI', 'sbi.co.in'], ATMSBI: ['State Bank of India', '#22409a', 'SBI', 'sbi.co.in'], SBIUPI: ['State Bank of India', '#22409a', 'SBI', 'sbi.co.in'], SBIBKS: ['State Bank of India', '#22409a', 'SBI', 'sbi.co.in'],
    HDFCBK: ['HDFC Bank', '#004c8f', 'HDFC', 'hdfcbank.com'], HDFCBN: ['HDFC Bank', '#004c8f', 'HDFC', 'hdfcbank.com'], HDFCCC: ['HDFC Bank Card', '#004c8f', 'HDFC', 'hdfcbank.com'],
    KOTAKB: ['Kotak Bank', '#ed1c24', 'KO', 'kotak.com'], KOTAKD: ['Kotak Bank', '#ed1c24', 'KO', 'kotak.com'], KOTAKM: ['Kotak Bank', '#ed1c24', 'KO', 'kotak.com'],
    ICICIB: ['ICICI Bank', '#f37e20', 'IC', 'icicibank.com'], ICICIT: ['ICICI Bank', '#f37e20', 'IC', 'icicibank.com'], AXISBK: ['Axis Bank', '#97144d', 'AX', 'axisbank.com'],
    PNBSMS: ['Punjab National Bank', '#a20a3a', 'PNB', 'pnbindia.in'], BOIIND: ['Bank of India', '#f36f21', 'BOI', 'bankofindia.co.in'], CANBNK: ['Canara Bank', '#0d7abf', 'CAN', 'canarabank.com'],
    UNIONB: ['Union Bank', '#e31e24', 'UB', 'unionbankofindia.co.in'], BOBTXN: ['Bank of Baroda', '#f26522', 'BOB', 'bankofbaroda.in'], IDFCFB: ['IDFC First Bank', '#9c1d26', 'IDFC', 'idfcfirstbank.com'],
    INDUSB: ['IndusInd Bank', '#98272a', 'IND', 'indusind.com'], YESBNK: ['Yes Bank', '#00518f', 'YES', 'yesbank.in'], FINOBK: ['Fino Bank', '#00a0e3', 'FINO', 'finobank.com'],
    AIRBNK: ['Airtel Payments Bank', '#e40000', 'APB', 'airtel.in'], IPPBNK: ['India Post Payments Bank', '#d71920', 'IPPB', 'ippbonline.com'],
    DHANHQ: ['Dhan', '#2d5bff', 'DHAN', 'dhan.co'], FFYERS: ['Fyers', '#3b82f6', 'FY', 'fyers.in'], FYERSS: ['Fyers', '#3b82f6', 'FY', 'fyers.in'], ZERODH: ['Zerodha', '#387ed1', 'ZE', 'zerodha.com'],
    GROWWS: ['Groww', '#00b386', 'GR', 'groww.in'], UPSTOX: ['Upstox', '#5a298b', 'UP', 'upstox.com'], ANGELB: ['Angel One', '#1d3b90', 'AO', 'angelone.in'],
    NSETRA: ['NSE', '#1f4e8c', 'NSE', 'nseindia.com'], NSESMS: ['NSE', '#1f4e8c', 'NSE', 'nseindia.com'], NSEIPO: ['NSE', '#1f4e8c', 'NSE', 'nseindia.com'], BSELTD: ['BSE', '#b8860b', 'BSE', 'bseindia.com'],
    BSESMS: ['BSE', '#b8860b', 'BSE', 'bseindia.com'], CDSLTX: ['CDSL', '#1e3a8a', 'CDSL', 'cdslindia.com'], CDSLEV: ['CDSL', '#1e3a8a', 'CDSL', 'cdslindia.com'], NSDLCA: ['NSDL', '#0f5132', 'NSDL', 'nsdl.co.in'],
    JIOHTT: ['JioHotstar', '#0f1d6b', 'JH', 'jiohotstar.com'], JIOINF: ['Jio', '#0f3cc9', 'JIO', 'jio.com'], JIOPAY: ['Jio', '#0f3cc9', 'JIO', 'jio.com'], AIRTEL: ['Airtel', '#e40000', 'AIR', 'airtel.in'],
    VICARE: ['Vi', '#e91e63', 'Vi', 'myvi.in'], BSNLIN: ['BSNL', '#1c75bc', 'BSNL', 'bsnl.co.in'], LICHFL: ['LIC Housing Finance', '#0055a5', 'LIC', 'lichousing.com'], LICIND: ['LIC', '#0055a5', 'LIC', 'licindia.in'],
    UPPCLT: ['UPPCL (Electricity)', '#0b7a3e', '⚡', 'uppcl.org'], UIDAIG: ['UIDAI (Aadhaar)', '#b45309', 'AAD', 'uidai.gov.in'], EPFOHO: ['EPFO', '#1e40af', 'PF', 'epfindia.gov.in'],
    IRCTCI: ['IRCTC', '#213a8f', 'RAIL', 'irctc.co.in'], PAYTMB: ['Paytm', '#00baf2', 'PAY', 'paytm.com'], PHONPE: ['PhonePe', '#5f259f', 'PE', 'phonepe.com'], GPAYIN: ['Google Pay', '#1a73e8', 'GP', 'pay.google.com'],
    AMAZON: ['Amazon', '#232f3e', 'AMZ', 'amazon.in'], AMZNIN: ['Amazon', '#232f3e', 'AMZ', 'amazon.in'], FLPKRT: ['Flipkart', '#2874f0', 'FK', 'flipkart.com'], SWIGGY: ['Swiggy', '#fc8019', 'SW', 'swiggy.com'],
    ZOMATO: ['Zomato', '#e23744', 'ZO', 'zomato.com'], BLUDRT: ['Blue Dart', '#004a99', 'BD', 'bluedart.com'], DLVERY: ['Delhivery', '#d52b1e', 'DEL', 'delhivery.com'], TMANGO: ['Mango (OTP)', '#475569', 'OTP'],
  },
  prefix: [
    ['SBI', 'State Bank of India', '#22409a', 'SBI', 'sbi.co.in'], ['HDFC', 'HDFC Bank', '#004c8f', 'HDFC', 'hdfcbank.com'], ['KOTAK', 'Kotak Bank', '#ed1c24', 'KO', 'kotak.com'], ['ICICI', 'ICICI Bank', '#f37e20', 'IC', 'icicibank.com'],
    ['AXIS', 'Axis Bank', '#97144d', 'AX', 'axisbank.com'], ['NSE', 'NSE', '#1f4e8c', 'NSE', 'nseindia.com'], ['BSE', 'BSE', '#b8860b', 'BSE', 'bseindia.com'], ['CDSL', 'CDSL', '#1e3a8a', 'CDSL', 'cdslindia.com'],
    ['JIO', 'Jio', '#0f3cc9', 'JIO', 'jio.com'], ['AIRTEL', 'Airtel', '#e40000', 'AIR', 'airtel.in'], ['LIC', 'LIC', '#0055a5', 'LIC', 'licindia.in'], ['DHAN', 'Dhan', '#2d5bff', 'DHAN', 'dhan.co'],
    ['FYERS', 'Fyers', '#3b82f6', 'FY', 'fyers.in'], ['PAYTM', 'Paytm', '#00baf2', 'PAY', 'paytm.com'], ['IRCTC', 'IRCTC', '#213a8f', 'RAIL', 'irctc.co.in'],
  ],
};
