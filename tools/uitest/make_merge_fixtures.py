# Makes made-up Prerna / UDISE+ style student lists (no real children) for tools/uitest/merge.js.
import datetime, openpyxl, os, sys
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "fixtures")
os.makedirs(out, exist_ok=True)

# name, gender, dob, class, father, mother, address
kids = [
 ("Anika Verma", "Female", "25/01/2017", "Class 3", "Anil Verma", "Poonam", "Barsoma Nathaipur"),
 ("Ankush", "Male", "17/08/2015", "Class 4", "Rajkumar", "Rekha", "Barsoma Nathaipur"),
 ("Ruchi Kumari", "Female", "05/02/2016", "Class 3", "Ramesh Kumar", "Seema Devi", "Nathaipur"),
 ("Aman Kumar", "Male", "10/10/2016", "Class 3", "Suresh Kumar", "Kamla Devi", "Barsoma"),
 ("Amar Kumar", "Male", "02/04/2018", "Class 1", "Suresh Kumar", "Kamla Devi", "Barsoma"),
 ("Divyanshi", "Female", "24/04/2016", "Class 3", "Manish", "Moni Devi", "Barsoma"),
 ("Kavya", "Female", "20/09/2016", "Class 3", "Anil Kumar", "Radhika", "Barsoma"),
 ("Madhu", "Female", "17/07/2014", "Class 5", "Amit Kumar", "Urmila Devi", "Barsoma"),
 ("Mayank Verma", "Male", "12/11/2018", "Class 2", "Indresh Kumar Verma", "Kusum Verma", "Barsoma"),
 ("Naitri", "Female", "16/10/2015", "Class 3", "Ram Charan", "Suneeta Devi", "Barsoma"),
 ("Kanishk", "Male", "02/05/2015", "Class 4", "Jitendra Yadav", "Bebi", "Barsoma"),
 ("Bhumika", "Female", "04/03/2017", "Class 3", "Sunil", "Antima", "Barsoma"),
 ("Arpita", "Female", "16/02/2020", "Class 1", "Amit Yadav", "Kusum", "Barsoma"),
 ("Jesika", "Female", "04/12/2016", "Class 3", "Mahesh Kumar", "Santosh Kumari", "Rampur Garant"),
 ("Prachi", "Female", "09/09/2017", "Class 2", "Vinod", "Sangita", "Barsoma"),
]
CL = {"Class 1": "I", "Class 2": "II", "Class 3": "III", "Class 4": "IV", "Class 5": "V"}

def prerna_rows(sel):
    rows = []
    for i, k in enumerate(sel):
        n, g, dob, c, fa, mo, ad = k
        rows.append([i + 1, 150 + kids.index(k), n, g, dob, "01/04/2024", c, "0425%06d" % kids.index(k), fa, mo, ad, "VERIFIED", fa])
    return rows
PH = ["Sr.No", "SR Number", "Student Name", "Gender", "Student DOB", "Student Addmission Date", "Student Class", "Student Unique No", "Father Name", "Mother Name", "Student Address", "Student Aadhaar Status", "Guardian Name"]

# Prerna file 1: what Ronin's Data button makes (Check column first + Summary sheet with the site)
wb = openpyxl.Workbook(); ws = wb.active; ws.title = "Data"
ws.append(["जाँच / Check"] + PH)
for r in prerna_rows(kids[:9]): ws.append([""] + r)
s = wb.create_sheet("Summary"); s.append(["Page / पेज", "Teacher Level"]); s.append(["Site", "prernaup.in"])
wb.save(os.path.join(out, "Teacher_Level 2026-11-14.xlsx"))

# Prerna file 2: straight from the portal, title rows above the header; Divyanshi + Kavya are in both files
wb = openpyxl.Workbook(); ws = wb.active
ws.append(["PRERNA PORTAL - STUDENT LIST"]); ws.append(["School: PS Barsoma"]); ws.append([])
ws.append(PH)
for r in prerna_rows(kids[5:13] + [kids[13]]): ws.append(r)
wb.save(os.path.join(out, "prerna_class_list.xlsx"))

# UDISE+ file: other columns, other formats, some mistakes on purpose
wb = openpyxl.Workbook(); ws = wb.active
ws.append(["S.No.", "Student PEN", "Student Name", "Gender", "Date of Birth", "Class", "Section", "Admission No", "Father's Name", "Mother's Name", "Mobile Number", "Aadhaar Number", "Address"])
def ud(k, **ch):
    n, g, dob, c, fa, mo, ad = k
    d, m, y = dob.split("/")
    row = dict(name=n.upper(), g=g[0], dob=datetime.date(int(y), int(m), int(d)), c=CL[c], fa=fa.upper(), mo=mo.upper(), ad="VILLAGE " + ad.upper() + " POST JAISINGHPUR")
    row.update(ch)
    return row
U = [
 ud(kids[0]),                                     # same (only capital letters differ)
 ud(kids[1]),
 ud(kids[2], name="RUCHEE KUMARI"),               # child name spelling differs
 ud(kids[3]),                                     # Aman / Amar: brothers, must not be mixed up
 ud(kids[4]),
 ud(kids[5], mo="MONI"),                          # mother name differs
 ud(kids[6], dob=datetime.date(2016, 9, 2)),      # date of birth differs
 ud(kids[7], g="M"),                              # gender differs
 ud(kids[8], mo=""),                              # mother name empty on UDISE+
 ud(kids[9]),
 ud(kids[10]),
 ud(kids[11]),
 ud(kids[11]),                                    # Bhumika entered twice on UDISE+ (another PEN)
 ud(kids[14]),                                    # Prachi: only on UDISE+
]                                                 # Arpita + Jesika: only on Prerna
for i, r in enumerate(U):
    ws.append([i + 1, "2109%07d" % (i * 7 + 3), r["name"], r["g"], r["dob"], r["c"], "A", str(500 + i), r["fa"], r["mo"], "98765%05d" % i, "XXXX XXXX %04d" % (1000 + i), r["ad"]])
    ws.cell(row=ws.max_row, column=5).number_format = "dd-mm-yyyy"
wb.save(os.path.join(out, "UDISE_student_list.xlsx"))
print("ok", out)

# Teachers: a seniority list and two Google Form downloads (old and new)
T = [  # name, employee code, father / husband, dob, designation, joining, mobile
 ("Ramesh Chandra", "EH1001", "Shiv Prasad", "12/05/1975", "Head Master", "01/07/1999", "9000000001"),
 ("Sunita Devi", "EH1002", "Ram Kishor", "03/03/1980", "Assistant Teacher", "15/09/2005", "9000000002"),
 ("Anil Kumar Verma", "EH1003", "Mool Chand", "22/11/1982", "Assistant Teacher", "10/01/2009", "9000000003"),
 ("Pooja Singh", "EH1004", "Vijay Singh", "08/08/1988", "Assistant Teacher", "05/12/2013", "9000000004"),
 ("Mohd Arif", "EH1005", "Mohd Salim", "19/02/1985", "Shiksha Mitra", "01/07/2001", "9000000005"),
 ("Kiran Yadav", "EH1006", "Rajesh Yadav", "30/06/1990", "Assistant Teacher", "20/11/2016", "9000000006"),
]
wb = openpyxl.Workbook(); ws = wb.active
ws.append(["वरिष्ठता सूची 2026"]); ws.append([])
ws.append(["क्रम", "Teacher Name", "Employee Code", "Father/Husband Name", "Date of Birth", "Designation", "Date of First Appointment"])
for i, t in enumerate(T): ws.append([i + 1, t[0], t[1], t[2], t[3], t[4], t[5]])
wb.save(os.path.join(out, "Seniority list.xlsx"))
FH = ["Timestamp", "Name of Teacher", "Employee Code", "Father/Husband Name", "Date of Birth", "Designation", "Date of First Appointment", "Mobile Number"]
wb = openpyxl.Workbook(); ws = wb.active; ws.append(FH)
for t in T[:5]:
    r = list(t)
    if t[1] == "EH1003": r[5] = "10/01/2008"            # old form: wrong joining date
    ws.append(["10/1/2026 10:15:00", r[0], r[1], r[2], r[3], r[4], r[5], r[6]])
wb.save(os.path.join(out, "Seniority Form old (Responses).xlsx"))
wb = openpyxl.Workbook(); ws = wb.active; ws.append(FH)
ws.append(["11/2/2026 09:00:00", "Ramesh Chandra", "EH1001", "Shiv Prasad", "12/05/1975", "Head Master", "01/07/1999", "9000000001"])
ws.append(["11/2/2026 09:30:00", "Sunita Devi", "EH1002", "Ram Kishor", "03/03/1981", "Assistant Teacher", "15/09/2005", "9000000002"])  # first try: wrong year
ws.append(["11/3/2026 18:00:00", "Sunita Devi", "EH1002", "Ram Kishor", "03/03/1980", "Assistant Teacher", "15/09/2005", "9000000002"])  # filled again, right
ws.append(["11/2/2026 11:00:00", "Anil Kumar Verma", "EH1003", "Mool Chand", "22/11/1982", "Assistant Teacher", "10/01/2009", "9000000003"])
ws.append(["11/4/2026 12:00:00", "Kiran Yadav", "EH1006", "Rajesh Yadav", "30/06/1990", "Assistant Teacher", "20/11/2016", "9000000006"])
wb.save(os.path.join(out, "Seniority Form new (Responses).xlsx"))   # Pooja + Mohd Arif did not fill the new form
